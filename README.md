# Snowflake Snowpark Container Services LLM Plugin

With this plugin, you can leverage LLMs hosted in Snowpark Container Services (SPCS) as part of the LLM Mesh.

![Screenshot 2024-02-09 at 4 58 15 PM](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/155dc4dd-148f-484a-a6c5-6643a52b2b2c)

# Capabilities

- Custom LLM connection option
- Connect to a chat completion endpoint hosted in SPCS to use in Dataiku Prompt Studios, LLM recipes, and via the LLM Mesh python APIs
- Connect to an embedding endpoint hosted in SPCS to use in Dataiku Embed recipe for Retrieval Augmented Generation (RAG)

# Limitations

- Must use Dataiku >= v12.5.2
- Must use Snowflake Oauth to connect to SPCS
- Must deploy and maintain one’s own LLMs on SPCS (we have sample code below to help)

# Setup
## Setup resources on the Snowflake side
1. Ensure that Snowpark Container Services (including SPCS jobs) is activated in your Snowflake account (ask your Snowflake rep)

2. (In Snowflake as ACCOUNTADMIN) Run the below code to:
	a. Create a role with access to SCPS and grant this role to users
	b. Create two SPCS compute pools (for the main LLM and embedding LLM)
	c. Create a warehouse to run simple admin queries (or you can grant access to an existing warehouse to the DATAIKU_SPCS_ROLE)
	d. Create a database and schema to hold the LLM model registry
	e. Create a Snowflake OAUTH security integration  

``` sql
-- Start of Snowflake SQL instructions
-- We'll need the ACCOUNTADMIN role to create these roles and compute pools
USE ROLE ACCOUNTADMIN;

-- First create a network rule and external access integration. We'll store them in a dedicated DB/Schema
CREATE DATABASE NETWORK_DB;
CREATE SCHEMA NETWORK_SCHEMA;

CREATE OR REPLACE NETWORK RULE SNOWFLAKE_EGRESS_ACCESS
  MODE = EGRESS
  TYPE = HOST_PORT
  VALUE_LIST = ('0.0.0.0:443','0.0.0.0:80');

CREATE EXTERNAL ACCESS INTEGRATION SNOWFLAKE_EGRESS_ACCESS_INTEGRATION
  ALLOWED_NETWORK_RULES = (SNOWFLAKE_EGRESS_ACCESS)
  ENABLED = true;

-- create a new oauth integration
CREATE SECURITY INTEGRATION OAUTH_DATAIKU_SPCS
  TYPE = OAUTH
  ENABLED = TRUE
  OAUTH_CLIENT = CUSTOM
  OAUTH_CLIENT_TYPE = 'CONFIDENTIAL'
  OAUTH_REDIRECT_URI = 'https://<YOUR_DATAIKU_INSTANCE_URL>/dip/api/oauth2-callback'
  OAUTH_ISSUE_REFRESH_TOKENS = TRUE
  OAUTH_REFRESH_TOKEN_VALIDITY = 7776000
  BLOCKED_ROLES_LIST = ('SYSADMIN');

-- get the OAUTH_CLIENT_ID and OAUTH_CLIENT_SECRET
CALL SYSTEM$SHOW_OAUTH_CLIENT_SECRETS('OAUTH_DATAIKU_SPCS');

-- Create a role for Dataiku users to use when calling LLMs on SPCS
CREATE ROLE DATAIKU_SPCS_ROLE;
GRANT ROLE DATAIKU_SPCS_ROLE TO USER "<SNOWFLAKE_USER_TO_DEPLOY_AND_USE_MODELS>";

-- Grant access to the external access integration to the new role
GRANT USAGE ON INTEGRATION SNOWFLAKE_EGRESS_ACCESS_INTEGRATION TO ROLE DATAIKU_SPCS_ROLE;

-- Create a compute pool with GPU resources to host the LLM for chat completion. GPU_3 is the smallest type with GPUs
-- Grant usage of this compute pool to the DATAIKU_SPCS_ROLE
CREATE COMPUTE POOL DATAIKU_GPU_NV_S_MODEL_COMPUTE_POOL with instance_family=GPU_NV_S min_nodes=1 max_nodes=1;
GRANT USAGE ON COMPUTE POOL DATAIKU_GPU_NV_S_MODEL_COMPUTE_POOL to role DATAIKU_SPCS_ROLE;
GRANT MONITOR ON COMPUTE POOL DATAIKU_GPU_NV_S_MODEL_COMPUTE_POOL to role DATAIKU_SPCS_ROLE;

GRANT BIND SERVICE ENDPOINT on ACCOUNT to DATAIKU_SPCS_ROLE;

-- Create a compute pool with CPU resources to host the LLM for sentence embeddings. STANDARD_1 is the smallest type
-- Grant usage of this compute pool to the DATAIKU_SPCS_ROLE
CREATE COMPUTE POOL DATAIKU_CPU_X64_XS_EMBED_COMPUTE_POOL with instance_family=CPU_X64_XS min_nodes=1 max_nodes=1;
GRANT USAGE ON COMPUTE POOL DATAIKU_CPU_X64_XS_EMBED_COMPUTE_POOL to role DATAIKU_SPCS_ROLE;
GRANT MONITOR ON COMPUTE POOL DATAIKU_CPU_X64_XS_EMBED_COMPUTE_POOL to role DATAIKU_SPCS_ROLE;

CREATE OR REPLACE WAREHOUSE DATAIKU_SPCS_WAREHOUSE WITH
  WAREHOUSE_SIZE='X-SMALL'
  AUTO_SUSPEND = 60
  AUTO_RESUME = true
  INITIALLY_SUSPENDED=false;
  
GRANT ALL ON WAREHOUSE DATAIKU_SPCS_WAREHOUSE TO ROLE DATAIKU_SPCS_ROLE;

CREATE DATABASE DATAIKU_SPCS;
GRANT OWNERSHIP ON DATABASE DATAIKU_SPCS TO ROLE DATAIKU_SPCS_ROLE;

USE ROLE DATAIKU_SPCS_ROLE;
USE DATABASE DATAIKU_SPCS;
USE WAREHOUSE DATAIKU_SPCS_WAREHOUSE;
CREATE SCHEMA MODEL_REGISTRY;
USE SCHEMA MODEL_REGISTRY;
-- End of Snowflake SQL instructions
```

## Setup the plugin in Dataiku
1. Install the plugin - Go to Plugins -> Add Plugin -> Fetch from Git repository, then enter this repo URL:

![add_plugin_from_git_repo](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/72b5ec9a-e037-4d83-a6b4-e8fbf16018d5)

![clone_plugin](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/a33f9925-6baf-4476-8695-2954056cf5b4)

3. In the plugin settings, add a “Snowflake login with SSO” preset:

![spcs_plugin_oauth_params](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/938ba63f-6713-4820-9462-9474f8ec6708)

4. In the Admin -> Connections page, create a new Snowflake connection called “spcs-access-only”. Enter the Snowflake DB, Schema, Warehouse, and Oauth info from the Snowflake-side setup. The “Auth authorization endpoint” should look like https://<YOUR_SNOWFLAKE_ACCOUNT>.aws.snowflakecomputing.com/oauth/authorize and the “Auth token endpoint” should look like https://<YOUR_SNOWFLAKE_ACCOUNT>.aws.snowflakecomputing.com/oauth/token-request. Uncheck “allow write” on the right in order to prevent users from creating datasets in this connection. We’ll use it to deploy models to SPCS only. Change the credentials mode to “per user”

![spcs_access_connection](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/4fd6fa52-9b02-4479-af03-9d95c5f285bb)

![spcs_access_connection_2](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/b99d299b-fa7d-4612-948a-228d9fdb1010)

5. Go to you user profile credentials, and go through the Oauth dance for both the “spcs-access-only” connection and “snowpark-container-services-llm” plugin

![spcs_user_oauth](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/5e5dc06b-a4ab-49c0-b319-f4e283265d41)
![spcs_user_oauth_2](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/c832f93b-d5dc-498f-9916-e875c94e1217)


6. Create a python 3.8 code environment (I named it “py_38_snowpark_llms”) and add the following packages:
```
scikit-learn==1.2.1
mlflow==1.30.0
statsmodels==0.12.2
protobuf==3.16.0
xgboost==1.7.3
lightgbm==3.3.5
matplotlib==3.7.4
scipy==1.10.1
snowflake-snowpark-python==1.12.1
snowflake-snowpark-python[pandas]
snowflake-connector-python[pandas]
MarkupSafe<2.1.0
cloudpickle==2.0.0
flask>=1.0,<1.1
itsdangerous<2.1.0
Jinja2>=2.11,<2.12
snowflake-ml-python==1.3.0
dash==2.15.0
dash_bootstrap_components==1.5.0
transformers==4.37.2
sentence-transformers==2.3.1
datasets==2.16.1
torch
sentencepiece==0.1.99
presidio-anonymizer==2.2.352
presidio_analyzer==2.2.352
spacy==3.7.3
langchain==0.0.347
bitsandbytes>0.37.0
```

6. Deploy LLM(s) to SPCS, then retrieve the resulting public endpoints for chat completion and embedding models. We have a sample notebook [here](Deploy_LLMs_to_Snowpark_Container_Services.ipynb) that shows how to deploy, for chat completion: Zephyr 7B-beta, Llama2, Phi2, and Falcon; and for text embeddings: MiniLM-L6-v2.

Your URLs should look something like: https://{ENDPOINT_ID}-{SNOWFLAKE_ACCOUNT_NAME/ACCOUNT_ID}.snowflakecomputing.app

7. Create a new “Custom LLM” connection, then choose the “Snowpark Container Services LLM” plugin.

![create_spcs_llm_connection](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/26b9b008-c4ab-4909-afc3-5f7717e56dc1)

Add a model for the main LLM, give it a name, chose the "Chat completion" capability, "Snowpark Container Services LLM" type, choose your Oauth preset, then enter the generated LLM endpoint URL from earlier. Enter your Snowflake account URL, the compute pool credit cost, and Snowflake credit cost (talk to your SNowflake rep for these). Max parallelism of queries is up to you. Start with 1 or 2.

![chat_completion_spcs_model_in_connection](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/ed61fbcb-cecd-4c88-b7ca-80922321eab7)

8. Add another model (in this same connection) for the text embedding model. Give it a name, choose the "Text embedding" capability, "Snowpark Container Services LLM" type, choose your Oauth preset, then enter the other generated LLM endpoint URL from the text embedding model. This model will likely have a different compute pool cost, depending on what you set up on the Snowflake side.

![text_embedding_spcs_model_in_connection](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/c0ba3669-e4a6-473b-8f76-f91f0af691e7)

10. Your chat completion LLM is now ready to use in LLM mesh!

![spcs_chat_completion_model_prompt_studio](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/075a49b9-d8ed-4814-96ef-23b04b9aec54)

12. As is your embedding LLM to generate embeddings for Retrieval Augmented Generation (RAG)!

![spcs_text_embedding_model_knowledge_bank](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/631fe96c-616f-4ea3-8c41-ff2c9ee5b98a)

14. Here is sample SQL code to run from Snowflake to DROP your LLM services and compute pools:

```sql
--must drop a service before dropping a compute pool
SHOW SERVICES;
DROP SERVICE <SERVICE_ID_FROM_ABOVE_RESULTS>;

DROP COMPUTE POOL DATAIKU_GPU_NV_S_MODEL_COMPUTE_POOL;
DROP COMPUTE POOL DATAIKU_CPU_X64_XS_EMBED_COMPUTE_POOL;
```
