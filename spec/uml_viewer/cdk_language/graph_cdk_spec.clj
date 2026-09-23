(ns uml-viewer.cdk-language.graph-cdk-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.cdk-language.graph-cdk :as cdk-graph]))

(def base-template
  "{\"Resources\": {
     \"Key\": {\"Type\": \"AWS::KMS::Key\",
              \"Properties\": {\"EnableKeyRotation\": true},
              \"Metadata\": {\"aws:cdk:path\": \"Base/Key/Resource\"}},
     \"Table\": {\"Type\": \"AWS::DynamoDB::Table\",
                \"DeletionPolicy\": \"Retain\",
                \"Properties\": {\"TableName\": \"app-sessions\",
                                 \"KeySchema\": [{\"AttributeName\": \"id\", \"KeyType\": \"HASH\"}],
                                 \"PointInTimeRecoverySpecification\": {\"PointInTimeRecoveryEnabled\": true},
                                 \"SSESpecification\": {\"SSEEnabled\": true,
                                                        \"KMSMasterKeyId\": {\"Fn::GetAtt\": [\"Key\", \"Arn\"]}}},
                \"Metadata\": {\"aws:cdk:path\": \"Base/Sessions/Resource\"}},
     \"Bucket\": {\"Type\": \"AWS::S3::Bucket\",
                 \"Properties\": {\"BucketName\": \"app-docs\",
                                  \"PublicAccessBlockConfiguration\": {
                                    \"BlockPublicAcls\": true, \"BlockPublicPolicy\": true,
                                    \"IgnorePublicAcls\": true, \"RestrictPublicBuckets\": true}},
                 \"Metadata\": {\"aws:cdk:path\": \"Base/Docs/Resource\"}},
     \"TableArn\": {\"Type\": \"AWS::SSM::Parameter\",
                   \"Properties\": {\"Name\": \"/app/table-arn\",
                                    \"Value\": {\"Fn::GetAtt\": [\"Table\", \"Arn\"]}}},
     \"BucketName\": {\"Type\": \"AWS::SSM::Parameter\",
                     \"Properties\": {\"Name\": \"/app/bucket-name\", \"Value\": \"app-docs\"}},
     \"CDKMetadata\": {\"Type\": \"AWS::CDK::Metadata\"}}}")

(def api-template
  "{\"Parameters\": {
     \"TableArnParam\": {\"Type\": \"AWS::SSM::Parameter::Value<String>\",
                        \"Default\": \"/app/table-arn\"},
     \"BucketParam\": {\"Type\": \"AWS::SSM::Parameter::Value<String>\",
                      \"Default\": \"/app/bucket-name\"}},
   \"Resources\": {
     \"Role\": {\"Type\": \"AWS::IAM::Role\",
               \"Properties\": {
                 \"AssumeRolePolicyDocument\": {\"Statement\": [
                   {\"Effect\": \"Allow\", \"Action\": \"sts:AssumeRole\",
                    \"Principal\": {\"Service\": \"lambda.amazonaws.com\"}}]},
                 \"ManagedPolicyArns\": [{\"Fn::Join\": [\"\", [\"arn:\", {\"Ref\": \"AWS::Partition\"},
                   \":iam::aws:policy/service-role/AWSLambdaBasicExecutionRole\"]]}]},
               \"Metadata\": {\"aws:cdk:path\": \"Api/Role/Resource\"}},
     \"Policy\": {\"Type\": \"AWS::IAM::Policy\",
                 \"Properties\": {
                   \"Roles\": [{\"Ref\": \"Role\"}],
                   \"PolicyDocument\": {\"Statement\": [
                     {\"Effect\": \"Allow\", \"Action\": \"dynamodb:GetItem\",
                      \"Resource\": {\"Fn::Join\": [\"\", [\"arn:aws:dynamodb:\",
                                                     {\"Ref\": \"AWS::Region\"},
                                                     \":1:table/app-*\"]]}},
                     {\"Effect\": \"Allow\", \"Action\": \"ssm:GetParameter\",
                      \"Resource\": \"arn:aws:ssm:*:*:parameter/app/*\"},
                     {\"Effect\": \"Allow\", \"Action\": [\"dynamodb:PutItem\", \"dynamodb:GetItem\"],
                      \"Resource\": {\"Ref\": \"TableArnParam\"}}]}}},
     \"Fn\": {\"Type\": \"AWS::Lambda::Function\",
             \"Properties\": {
               \"Runtime\": \"python3.12\",
               \"Role\": {\"Fn::GetAtt\": [\"Role\", \"Arn\"]},
               \"Environment\": {\"Variables\": {
                 \"TABLE\": {\"Ref\": \"TableArnParam\"},
                 \"BUCKET\": {\"Ref\": \"BucketParam\"},
                 \"SECRET_TOKEN\": \"s3cr3t-value-123\",
                 \"SELF\": {\"Fn::Sub\": \"${Role.Arn}\"}}}},
             \"Metadata\": {\"aws:cdk:path\": \"Api/Handler/Resource\"}}}}")

(defn- synth-dir []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "uml-cdk-graph-" (System/nanoTime)))]
    (doseq [[rel body] [["infra/Base.template.json" base-template]
                        ["app/Api.template.json" api-template]]]
      (let [f (io/file dir rel)]
        (io/make-parents f)
        (spit f body)))
    dir))

(defn- texts [c k]
  (mapv :text (get c k)))

(describe "cdk graph"
  (with-all scanned (graph/scan (graph/lookup :cdk) (synth-dir) {}))
  (with-all by-id (into {} (map (juxt :id identity) (:classes @scanned))))

  (it "registers under :cdk"
    (should-be-same cdk-graph/impl (graph/lookup :cdk)))

  (it "pipes the scanner on stdin with the synth root"
    (should= ["python3" "-" "/out"] (cdk-graph/scan-command "python3" "/out")))

  (it "makes one box per resource, named by its construct path"
    (should= #{:Base.Key :Base.Sessions :Base.Docs :Api.Role :Api.Handler} (set (keys @by-id)))
    (should= "Handler" (get-in @by-id [:Api.Handler :name]))
    (should= "Api/Handler/Resource" (get-in @by-id [:Api.Handler :ns]))
    (should= :DynamoDB.Table (get-in @by-id [:Base.Sessions :stereotype]))
    (should= :Lambda.Function (get-in @by-id [:Api.Handler :stereotype]))
    (should= :cdk (get-in @by-id [:Api.Handler :lang])))

  (it "links refs within a template"
    (let [edges (set (map (juxt :from :to :kind) (:edges @scanned)))]
      (should (contains? edges [:Api.Handler :Api.Role :dependency]))))

  (it "links across stacks through SSM parameters, by ref or by literal name"
    (let [edges (set (map (juxt :from :to) (:edges @scanned)))]
      (should (contains? edges [:Api.Handler :Base.Sessions]))
      (should (contains? edges [:Api.Handler :Base.Docs]))))

  (it "turns IAM statement ARNs into role edges, matching typed globs by name"
    (let [edges (set (map (juxt :from :to) (:edges @scanned)))]
      (should (contains? edges [:Api.Role :Base.Sessions]))
      (should-not (contains? edges [:Api.Role :Base.Docs]))))

  (it "drops bookkeeping resources"
    (should-not (some #(re-find #"(?i)metadata|policy|param" (name (:id %)))
                      (:classes @scanned))))

  (it "lists review facts as fields, naming referenced resources"
    (should= ["key: id" "pitr: on" "kms: Key" "deletion protection: off" "on delete: Retain"]
             (texts (@by-id :Base.Sessions) :fields))
    (should= ["rotation: on"] (texts (@by-id :Base.Key) :fields))
    (should= ["public access: blocked" "encryption: S3 default" "versioning: off" "access logs: off"]
             (texts (@by-id :Base.Docs) :fields))
    (should= ["assumed by: lambda"] (texts (@by-id :Api.Role) :fields))
    (should= ["runtime: python3.12" "in vpc: no" "env: BUCKET, SECRET_TOKEN, SELF, TABLE"]
             (texts (@by-id :Api.Handler) :fields)))

  (it "never copies env var values into the graph"
    (should-not (str/includes? (pr-str @scanned) "s3cr3t-value-123")))

  (it "lists a role's permissions as ops, broad grants first, managed policies last"
    (should= ["!! dynamodb table/app-*  dynamodb: GetItem"
              "!! ssm parameter/app/*  ssm: GetParameter"
              "Sessions  dynamodb: GetItem, PutItem"
              "managed  AWSLambdaBasicExecutionRole"]
             (texts (@by-id :Api.Role) :ops))
    (should-be-nil (:ops (@by-id :Api.Handler)))))
