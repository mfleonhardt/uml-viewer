"""CDK LanguageGraph scanner for uml-viewer.

Reads CloudFormation templates from `cdk synth` (every *.template.json under
ROOT, any depth) and prints a deployment graph as EDN on stdout:

    {:classes [{:id :Stack.Construct.Res :name "Res" :ns "Stack/Construct/Res"
                :stereotype :DynamoDB.Table :lang :cdk} ...]
     :edges [{:from :Stack.A :to :Stack.B :kind :dependency} ...]}

The tree is the CDK construct path (aws:cdk:path): stack, then constructs,
then resources. A trailing `Resource` or `Default` segment is dropped.

Edges:
- A resource that refers to another (Ref, Fn::GetAtt, Fn::Sub) depends on it.
- Cross-stack: a template parameter of type AWS::SSM::Parameter::Value<...>
  whose default names an SSM parameter written by another template resolves
  to the resource(s) that parameter's value refers to. A literal value
  (e.g. a bucket name) resolves to the box in that stack with that name.
- IAM: each role an AWS::IAM::Policy / ManagedPolicy is attached to (and
  each role, for its inline Policies) depends on what its statements name:
  refs, exact SSM parameter ARNs, and typed ARNs like `...:table/prefix-*`,
  which match boxes of that type whose name fits the glob. SSM wildcards
  are not expanded.

Bookkeeping resources (SSM parameters, IAM policies, CDK metadata, lambda
permissions, route/association plumbing, aliases, bucket policies) are not
boxes; SSM parameters and policies become edges instead.

Card rows:
- :fields are review facts per resource type (encryption, PITR, public
  access, retention, trust principals, env var and secret *names*, ...) plus
  DeletionPolicy. Values are never copied from env vars, secrets, or
  policy conditions.
- :ops on a role are its IAM permissions, one row per (effect, target) with
  actions grouped by service. `!!` marks a broad grant (resource `*`, an ARN
  whose resource part has a wildcard, or action `*` / `svc:*`); broad rows
  sort first. `(if)` marks a Condition. AWS managed policies come last.

Usage: python3 scan_cdk.py ROOT
"""

import fnmatch
import json
import re
import sys
from pathlib import Path

FOLDED = {
    "AWS::CDK::Metadata",
    "AWS::SSM::Parameter",
    "AWS::IAM::Policy",
    "AWS::IAM::ManagedPolicy",
    "AWS::Lambda::Permission",
    "AWS::EC2::SubnetRouteTableAssociation",
    "AWS::EC2::Route",
    "AWS::EC2::SecurityGroupIngress",
    "AWS::EC2::SecurityGroupEgress",
    "AWS::KMS::Alias",
    "AWS::S3::BucketPolicy",
}
POLICY_TYPES = {"AWS::IAM::Policy", "AWS::IAM::ManagedPolicy"}
SSM_PARAM_TYPE = re.compile(r"^AWS::SSM::Parameter::Value<")
SUB_VAR = re.compile(r"\$\{([A-Za-z0-9]+)(?:\.[A-Za-z0-9.]+)?\}")
TRAILING = {"Resource", "Default"}


def refs(node):
    """Logical ids referred to anywhere inside `node`."""
    out = set()
    if isinstance(node, dict):
        if "Ref" in node and isinstance(node["Ref"], str):
            out.add(node["Ref"])
        if "Fn::GetAtt" in node:
            att = node["Fn::GetAtt"]
            if isinstance(att, list) and att and isinstance(att[0], str):
                out.add(att[0])
            elif isinstance(att, str):
                out.add(att.split(".")[0])
        if "Fn::Sub" in node:
            sub = node["Fn::Sub"]
            text = sub[0] if isinstance(sub, list) else sub
            if isinstance(text, str):
                out.update(SUB_VAR.findall(text))
        for v in node.values():
            out |= refs(v)
    elif isinstance(node, list):
        for v in node:
            out |= refs(v)
    return out


def flat_strings(node):
    """Literal strings inside `node`; each Fn::Join is flattened with every
    non-literal part (Ref, GetAtt, pseudo parameters) written as `*`."""
    if isinstance(node, str):
        return [node]
    if isinstance(node, dict):
        if "Fn::Join" in node:
            sep, parts = node["Fn::Join"]
            return [sep.join(p if isinstance(p, str) else "*" for p in parts)]
        return [s for v in node.values() for s in flat_strings(v)]
    if isinstance(node, list):
        return [s for v in node for s in flat_strings(v)]
    return []


ARN = re.compile(r"^arn:[^:]*:([^:]+):[^:]*:[^:]*:([A-Za-z-]+)[/:](.+)$")


def arn_patterns(node):
    """(service, resource-type, name-glob) for each ARN string in `node`.
    SSM parameter ARNs are left to the SSM-name rule."""
    out = []
    for s in flat_strings(node):
        m = ARN.match(s)
        if m and m.group(1) != "ssm":
            out.append((m.group(1).replace("-", "").lower(), m.group(2).lower(),
                        m.group(3).split("/")[0]))
    return out


def ssm_names_in(node):
    """SSM parameter names named by exact ARNs (`...:parameter/name`)."""
    out = set()
    for s in flat_strings(node):
        i = s.find(":parameter/")
        if s.startswith("arn:") and i >= 0 and "*" not in s[i:]:
            out.add(s[i + len(":parameter"):])
    return out


def segment(s):
    return re.sub(r"[^A-Za-z0-9_-]", "_", s) or "_"


def resource_path(stack, logical_id, res):
    path = res.get("Metadata", {}).get("aws:cdk:path")
    parts = path.split("/") if isinstance(path, str) else [stack, logical_id]
    if len(parts) > 2 and parts[-1] in TRAILING:
        parts = parts[:-1]
    return parts


def short_type(t):
    """`AWS::DynamoDB::Table` -> `DynamoDB.Table` (a valid EDN keyword)."""
    t = t[len("AWS::"):] if t.startswith("AWS::") else t
    return segment(t.replace("::", ".")).replace("_", ".") if t else "Unknown"


def edn_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def edn_map(m):
    items = []
    for k, v in m.items():
        if isinstance(v, list):
            items.append(f":{k} [" + " ".join(edn_map(x) for x in v) + "]")
        elif k in ("id", "from", "to", "kind", "stereotype", "lang"):
            items.append(f":{k} :{v}")
        else:
            items.append(f":{k} {edn_str(v)}")
    return "{" + " ".join(items) + "}"


class Template:
    def __init__(self, path):
        doc = json.loads(path.read_text(encoding="utf-8"))
        self.stack = path.name[: -len(".template.json")]
        self.resources = doc.get("Resources", {}) or {}
        self.parameters = doc.get("Parameters", {}) or {}
        self.ids = {}

    def ssm_inputs(self):
        """Template parameter logical id -> SSM parameter name it reads."""
        return {
            pid: p["Default"]
            for pid, p in self.parameters.items()
            if SSM_PARAM_TYPE.match(str(p.get("Type", "")))
            and isinstance(p.get("Default"), str)
        }


def assign_ids(templates):
    taken = set()
    for t in templates:
        for lid, res in t.resources.items():
            if res.get("Type") in FOLDED:
                continue
            parts = [segment(p) for p in resource_path(t.stack, lid, res)]
            cid = ".".join(parts)
            if cid in taken:
                cid = f"{cid}.{segment(lid)}"
            taken.add(cid)
            t.ids[lid] = cid


def box_names(templates):
    """Box id -> (CFN type, literal top-level property strings, e.g. names)."""
    out = {}
    for t in templates:
        for lid, cid in t.ids.items():
            res = t.resources[lid]
            props = res.get("Properties", {}) or {}
            names = [s for v in props.values()
                     for s in flat_strings(v) if isinstance(v, (str, dict))]
            out[cid] = (res.get("Type", ""), names)
    return out


def named(t, value, names):
    """Boxes in template `t` with a literal property equal to `value`."""
    return {cid for cid in t.ids.values() if value in names[cid][1]}


def ssm_producers(templates, names):
    """SSM parameter name -> box ids its value refers to (any template).
    A literal value resolves to the box in that stack with that name."""
    out = {}
    for t in templates:
        for res in t.resources.values():
            if res.get("Type") != "AWS::SSM::Parameter":
                continue
            props = res.get("Properties", {})
            name = props.get("Name")
            if not isinstance(name, str):
                continue
            value = props.get("Value")
            targets = {t.ids[r] for r in refs(value) if r in t.ids}
            if not targets and isinstance(value, str):
                targets = named(t, value, names)
            out.setdefault(name, set()).update(targets)
    return out


# ARN resource types that differ from the CloudFormation type name.
ARN_TYPE_ALIASES = {("s3vectors", "bucket"): "vectorbucket"}


def arn_targets(node, names):
    """Boxes whose type and name match an ARN in `node` (IAM statements)."""
    out = set()
    for svc, rtype, glob in arn_patterns(node):
        rtype = ARN_TYPE_ALIASES.get((svc, rtype), rtype)
        for cid, (cfn_type, strs) in names.items():
            parts = cfn_type.split("::")
            if len(parts) != 3 or parts[1].lower() != svc or parts[2].lower() != rtype:
                continue
            if glob == "*" or any(fnmatch.fnmatchcase(s, glob) for s in strs):
                out.add(cid)
    return out


def resolver(t, producers):
    inputs = t.ssm_inputs()

    def resolve(ids):
        out = set()
        for r in ids:
            if r in t.ids:
                out.add(t.ids[r])
            elif r in inputs:
                out |= producers.get(inputs[r], set())
        return out

    return resolve


def grants(node, resolve, producers, names):
    """Boxes an IAM policy document in `node` names: refs, exact SSM
    parameter ARNs, and typed resource ARNs (globs allowed)."""
    out = resolve(refs(node)) | arn_targets(node, names)
    for n in ssm_names_in(node):
        out |= producers.get(n, set())
    return out


# ---------------------------------------------------------------- facts ----
# Card fields: settings worth reviewing, per resource type. Only names and
# settings are shown; env var values and secret values are never read out.

MAX_FIELDS = 6


def box_label(cid):
    return cid.rpartition(".")[2]


def listing(items, keep=4):
    items = [str(i) for i in items]
    head = ", ".join(items[:keep])
    return head + (f" +{len(items) - keep}" if len(items) > keep else "")


def on_off(v):
    return "on" if v in (True, "true", "Enabled", "ENABLED") else "off"


def dig(node, *keys):
    for k in keys:
        if isinstance(node, dict):
            node = node.get(k)
        elif isinstance(node, list) and isinstance(k, int) and len(node) > k:
            node = node[k]
        else:
            return None
    return node


def short_arn(s):
    """`arn:aws:ssm:*:*:parameter/x/*` -> `ssm parameter/x/*`."""
    if isinstance(s, str) and s.startswith("arn:"):
        parts = s.split(":", 5)
        if len(parts) == 6:
            return f"{parts[2]} {parts[5]}"
    return s


def namer(t, resolve):
    """Label for a property value: resolved box names, else the SSM
    parameter a template input reads, else a short literal."""
    inputs = t.ssm_inputs()

    def name_of(node):
        boxes = resolve(refs(node))
        if boxes:
            return listing(sorted(box_label(b) for b in boxes), 2)
        for r in sorted(refs(node)):
            if r in inputs:
                return f"ssm {inputs[r]}"
        strs = flat_strings(node)
        return short_arn(strs[0]) if strs else "?"

    return name_of


def table_facts(p, name_of):
    keys = {k.get("KeyType"): k.get("AttributeName") for k in p.get("KeySchema", [])}
    sse = p.get("SSESpecification") or {}
    out = [f"key: {listing([v for v in (keys.get('HASH'), keys.get('RANGE')) if v])}",
           f"pitr: {on_off(dig(p, 'PointInTimeRecoverySpecification', 'PointInTimeRecoveryEnabled'))}"]
    if sse.get("KMSMasterKeyId"):
        out.append(f"kms: {name_of(sse['KMSMasterKeyId'])}")
    elif sse.get("SSEEnabled") in (True, "true"):
        out.append("kms: AWS managed key")
    else:
        out.append("encryption: AWS owned key")
    out.append(f"deletion protection: {on_off(p.get('DeletionProtectionEnabled'))}")
    if p.get("BillingMode"):
        out.append(f"billing: {p['BillingMode']}")
    if dig(p, "TimeToLiveSpecification", "Enabled") in (True, "true"):
        out.append(f"ttl: {dig(p, 'TimeToLiveSpecification', 'AttributeName')}")
    if dig(p, "StreamSpecification", "StreamViewType"):
        out.append(f"stream: {dig(p, 'StreamSpecification', 'StreamViewType')}")
    if p.get("GlobalSecondaryIndexes"):
        out.append(f"indexes: {len(p['GlobalSecondaryIndexes'])}")
    return out


def bucket_facts(p, name_of):
    pab = p.get("PublicAccessBlockConfiguration")
    flags = ["BlockPublicAcls", "BlockPublicPolicy", "IgnorePublicAcls", "RestrictPublicBuckets"]
    if not pab:
        public = "not set"
    elif all(pab.get(f) in (True, "true") for f in flags):
        public = "blocked"
    else:
        public = "partial"
    enc = dig(p, "BucketEncryption", "ServerSideEncryptionConfiguration", 0,
              "ServerSideEncryptionByDefault") or {}
    if enc.get("KMSMasterKeyID"):
        encryption = f"kms: {name_of(enc['KMSMasterKeyID'])}"
    elif enc.get("SSEAlgorithm"):
        encryption = f"encryption: {enc['SSEAlgorithm']}"
    else:
        encryption = "encryption: S3 default"
    out = [f"public access: {public}", encryption,
           f"versioning: {dig(p, 'VersioningConfiguration', 'Status') or 'off'}",
           f"access logs: {'on' if p.get('LoggingConfiguration') else 'off'}"]
    rules = dig(p, "LifecycleConfiguration", "Rules")
    if rules:
        out.append(f"lifecycle rules: {len(rules)}")
    cors = dig(p, "CorsConfiguration", "CorsRules")
    if cors:
        out.append(f"cors rules: {len(cors)}")
    return out


def lambda_facts(p, _name_of):
    out = [f"runtime: {p.get('Runtime') or ('image' if p.get('PackageType') == 'Image' else '?')}"]
    if p.get("MemorySize"):
        out.append(f"memory: {p['MemorySize']} MB")
    if p.get("Timeout"):
        out.append(f"timeout: {p['Timeout']} s")
    out.append(f"in vpc: {'yes' if p.get('VpcConfig') else 'no'}")
    env = dig(p, "Environment", "Variables") or {}
    if env:
        out.append(f"env: {listing(sorted(env))}")
    if p.get("ReservedConcurrentExecutions") is not None:
        out.append(f"reserved concurrency: {p['ReservedConcurrentExecutions']}")
    return out


def task_facts(p, _name_of):
    containers = p.get("ContainerDefinitions", []) or []
    env = sorted({e.get("Name") for c in containers for e in c.get("Environment", []) or []
                  if e.get("Name")})
    secrets = sorted({s.get("Name") for c in containers for s in c.get("Secrets", []) or []
                      if s.get("Name")})
    out = [f"cpu / memory: {p.get('Cpu', '?')} / {p.get('Memory', '?')}",
           f"containers: {listing([c.get('Name', '?') for c in containers])}"]
    if env:
        out.append(f"env: {listing(env)}")
    if secrets:
        out.append(f"secrets: {listing(secrets)}")
    if p.get("NetworkMode"):
        out.append(f"network: {p['NetworkMode']}")
    return out


def key_facts(p, _name_of):
    return [f"rotation: {on_off(p.get('EnableKeyRotation'))}"]


def log_facts(p, name_of):
    out = [f"retention: {p['RetentionInDays']} days" if p.get("RetentionInDays")
           else "retention: never expires"]
    if p.get("KmsKeyId"):
        out.append(f"kms: {name_of(p['KmsKeyId'])}")
    return out


def rule_text(rule, name_of):
    src = (rule.get("CidrIp") or rule.get("CidrIpv6")
           or (name_of(rule["SourceSecurityGroupId"]) if rule.get("SourceSecurityGroupId") else None)
           or (name_of(rule["DestinationSecurityGroupId"]) if rule.get("DestinationSecurityGroupId") else None)
           or "?")
    proto = str(rule.get("IpProtocol", "?"))
    if proto == "-1":
        return f"{src} all traffic"
    lo, hi = rule.get("FromPort"), rule.get("ToPort")
    ports = f"{lo}" if lo == hi else f"{lo}-{hi}"
    return f"{src} {proto}/{ports}"


def sg_facts(p, name_of, extra_ingress=()):
    ingress = [rule_text(r, name_of) for r in (p.get("SecurityGroupIngress") or [])]
    ingress += [rule_text(r, name_of) for r in extra_ingress]
    egress = [rule_text(r, name_of) for r in (p.get("SecurityGroupEgress") or [])]
    out = [f"ingress: {listing(ingress, 3) if ingress else 'none'}"]
    if egress:
        out.append(f"egress: {listing(egress, 3)}")
    return out


def principals(doc):
    out = []
    for s in (doc or {}).get("Statement", []) or []:
        pr = s.get("Principal") or {}
        if pr == "*":
            out.append("* (anyone)")
            continue
        for kind in ("Service", "AWS", "Federated"):
            vals = pr.get(kind)
            for v in (vals if isinstance(vals, list) else [vals] if vals else []):
                strs = flat_strings(v)
                label = strs[0] if strs else "?"
                out.append(label.replace(".amazonaws.com", "") if kind == "Service"
                           else short_arn(label))
    return sorted(set(out))


def role_facts(p, _name_of):
    return [f"assumed by: {listing(principals(p.get('AssumeRolePolicyDocument')), 3)}"]


def secret_facts(p, name_of):
    out = [f"kms: {name_of(p['KmsKeyId'])}" if p.get("KmsKeyId") else "kms: AWS managed key"]
    if p.get("GenerateSecretString"):
        out.append("value: generated by AWS")
    elif p.get("SecretString") is not None:
        literal = isinstance(p["SecretString"], str)
        out.append("value: literal in template" if literal else "value: from another resource")
    return out


def distribution_facts(p, _name_of):
    d = p.get("DistributionConfig") or {}
    return [f"viewer protocol: {dig(d, 'DefaultCacheBehavior', 'ViewerProtocolPolicy') or '?'}",
            f"min tls: {dig(d, 'ViewerCertificate', 'MinimumProtocolVersion') or 'default'}",
            f"waf: {'yes' if d.get('WebACLId') else 'no'}",
            f"access logs: {'on' if d.get('Logging') else 'off'}"]


FACTS = {
    "AWS::DynamoDB::Table": table_facts,
    "AWS::S3::Bucket": bucket_facts,
    "AWS::Lambda::Function": lambda_facts,
    "AWS::ECS::TaskDefinition": task_facts,
    "AWS::KMS::Key": key_facts,
    "AWS::Logs::LogGroup": log_facts,
    "AWS::IAM::Role": role_facts,
    "AWS::SecretsManager::Secret": secret_facts,
    "AWS::CloudFront::Distribution": distribution_facts,
}


def facts(res, name_of, extra_ingress=()):
    rtype = res.get("Type", "")
    props = res.get("Properties", {}) or {}
    if rtype == "AWS::EC2::SecurityGroup":
        out = sg_facts(props, name_of, extra_ingress)
    else:
        out = FACTS.get(rtype, lambda _p, _n: [])(props, name_of)
    out = out[:MAX_FIELDS]
    if res.get("DeletionPolicy"):
        out.append(f"on delete: {res['DeletionPolicy']}")
    return out


# ---------------------------------------------------------- permissions ----
# Role ops: one row per (effect, target), actions grouped by service.

PSEUDO = re.compile(r"^AWS::")


def as_list(x):
    return x if isinstance(x, list) else [] if x is None else [x]


def broad_literal(entry):
    """True when a resource entry without real refs names many things:
    `*`, or an ARN whose resource part contains a wildcard."""
    if any(not PSEUDO.match(r) for r in refs(entry)):
        return False
    for s in flat_strings(entry):
        if s == "*":
            return True
        if s.startswith("arn:"):
            parts = s.split(":", 5)
            if len(parts) == 6 and "*" in parts[5]:
                return True
    return False


def action_summary(actions):
    if "*" in actions:
        return "*"
    by_svc = {}
    for a in sorted(actions):
        svc, _, verb = a.partition(":")
        by_svc.setdefault(svc, []).append(verb or "*")
    return " · ".join(f"{svc}: {listing(verbs, 2)}" for svc, verbs in sorted(by_svc.items()))


def statement_rows(stmts, resolve, producers, names):
    """{(effect, label, broad, conditional): set(actions)} for IAM statements."""
    rows = {}
    for s in stmts:
        if not isinstance(s, dict):
            continue
        effect = s.get("Effect", "Allow")
        actions = [a for a in as_list(s.get("Action") or s.get("NotAction")) if isinstance(a, str)]
        if s.get("NotAction"):
            actions = [f"not {a}" for a in actions]
        cond = bool(s.get("Condition"))
        entries = as_list(s.get("Resource"))
        negated = "NotResource" in s
        if negated:
            entries = as_list(s.get("NotResource"))
        for entry in entries:
            boxes = grants(entry, resolve, producers, names)
            broad = negated or broad_literal(entry) or any(
                a == "*" or a.endswith(":*") for a in actions)
            if boxes and not broad_literal(entry):
                labels = sorted(box_label(b) for b in boxes)
            else:
                strs = flat_strings(entry)
                labels = [("not " if negated else "") + (short_arn(strs[0]) if strs else "?")]
            for label in labels:
                rows.setdefault((effect, label, broad, cond), set()).update(actions)
    return rows


def managed_rows(role_props):
    out = []
    for arn in as_list(role_props.get("ManagedPolicyArns")):
        strs = flat_strings(arn)
        if strs and ":iam::aws:policy/" in strs[0]:
            out.append(strs[0].rsplit("/", 1)[-1])
    return sorted(out)


def ops_for(rows, managed):
    ordered = sorted(rows.items(), key=lambda kv: (not kv[0][2], kv[0][0] != "Deny", kv[0][1]))
    ops = []
    for (effect, label, broad, cond), actions in ordered:
        text = (("!! " if broad else "") + ("deny " if effect == "Deny" else "")
                + f"{label}  {action_summary(actions)}" + ("  (if)" if cond else ""))
        ops.append({"name": text, "text": text})
    for m in managed:
        text = f"managed  {m}"
        ops.append({"name": text, "text": text})
    return ops


def scan(root):
    paths = sorted(Path(root).resolve().rglob("*.template.json"))
    templates = [Template(p) for p in paths]
    assign_ids(templates)
    names = box_names(templates)
    producers = ssm_producers(templates, names)

    classes, edges = [], set()
    role_rows = {}     # role box id -> {(effect, label, broad, cond): actions}
    ingress = {}       # security group box id -> [standalone ingress rules]

    def grant(role, stmts, resolve):
        stmts = [s for s in as_list(stmts) if isinstance(s, dict)]
        targets = grants(stmts, resolve, producers, names)
        edges.update((role, x) for x in targets if x != role)
        rows = role_rows.setdefault(role, {})
        for key, actions in statement_rows(stmts, resolve, producers, names).items():
            rows.setdefault(key, set()).update(actions)

    for t in templates:
        resolve = resolver(t, producers)
        for lid, res in t.resources.items():
            rtype = res.get("Type", "")
            props = res.get("Properties", {}) or {}
            if rtype in POLICY_TYPES:
                stmts = dig(props, "PolicyDocument", "Statement")
                for role in resolve(refs(props.get("Roles", []))):
                    grant(role, stmts, resolve)
            elif rtype == "AWS::EC2::SecurityGroupIngress":
                for sg in resolve(refs(props.get("GroupId"))):
                    ingress.setdefault(sg, []).append(props)
            elif rtype == "AWS::IAM::Role" and lid in t.ids:
                role = t.ids[lid]
                for pol in as_list(props.get("Policies")):
                    grant(role, dig(pol, "PolicyDocument", "Statement"), resolve)
                for arn in as_list(props.get("ManagedPolicyArns")):
                    for r in refs(arn):
                        mp = t.resources.get(r, {})
                        if mp.get("Type") == "AWS::IAM::ManagedPolicy":
                            grant(role, dig(mp, "Properties", "PolicyDocument", "Statement"), resolve)

    for t in templates:
        resolve = resolver(t, producers)
        name_of = namer(t, resolve)
        for lid, res in t.resources.items():
            if lid not in t.ids:
                continue
            rtype = res.get("Type", "")
            props = res.get("Properties", {}) or {}
            cid = t.ids[lid]
            path = res.get("Metadata", {}).get("aws:cdk:path") or f"{t.stack}/{lid}"
            c = {"id": cid, "name": box_label(cid), "ns": path,
                 "stereotype": short_type(rtype), "lang": "cdk"}
            fields = facts(res, name_of, ingress.get(cid, ()))
            if fields:
                c["fields"] = [{"text": f} for f in fields]
            ops = ops_for(role_rows.get(cid, {}),
                          managed_rows(props) if rtype == "AWS::IAM::Role" else [])
            if ops:
                c["ops"] = ops
            classes.append(c)
            deps = refs(props) | refs(res.get("Condition", {}))
            edges.update((cid, x) for x in resolve(deps) if x != cid)

    edge_maps = [{"from": a, "to": b, "kind": "dependency"} for a, b in sorted(edges)]
    return classes, edge_maps


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    classes, edges = scan(argv[1])
    out = sys.stdout
    out.write("{:classes [\n")
    for c in classes:
        out.write(" " + edn_map(c) + "\n")
    out.write("]\n :edges [\n")
    for e in edges:
        out.write(" " + edn_map(e) + "\n")
    out.write("]}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
