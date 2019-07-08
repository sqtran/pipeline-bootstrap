# pipeline-bootstrap

An example pipeline that builds other pipelines, for CICD operations in Openshift.

This project is Work in Progress, and may have a lot of external dependencies not listed here.  It'll get better though, so stick with it.


### Dependencies

#### Jenkins Credentials

###### Username with Password

- git-sa  - allows Pipeline to connect to SCM repository

###### Secret Text
- jenkins-sa-token - OCP service account token to allow Pipeline to connect to OCP.  Secret Text because it's used outside of the OCP Client Plugin too.
- jfrog-api-key - Used to authenticate to Artifactory

###### Openshift Client Plugin Token
- ocp-qa-token - For connecting to QA Cluster, this is the jenkins serviceaccount token in OCP in QA
- ocp-prod-token - For connecting to PROD Cluster, this is the jenkins serviceaccount token in OCP in PROD


#### Jenkins Managed Files
- custom-settings.xml - Any specific maven settings configurations
- docker_config.json  - Docker-format for AuthNZ, used by OCP to copy images between registries

#### Kubernetes Pod Template Environment Variables
- OPENSHIFT_JENKINS_JVM_ARCH - set to `x86_64`

#### Jenkins Global Environment Variables
- OCPDEV_REGISTRY_URL  - points to the OCP DEV registry
- ARTIFACTORY_URL - hostname of the external image registry, should probably rename this to something vendor agnostic


## Plugins
- list of plugins here....
