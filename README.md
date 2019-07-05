# pipeline-bootstrap

An example pipeline that builds other pipelines, for CICD operations in Openshift.

This project is Work in Progress, and may have a lot of external dependencies not listed here.  It'll get better though, so stick with it.


### Dependencies

#### Jenkins Credentials
jenkins-sa-token - OCP service account token to allow Pipeline to connect to OCP
git-sa  - allows Pipeline to connect to Git Repository

ocp-qa-token - Jenkins service account token for connecting to QA
ocp-prod-token - Jenkins service account token for connecting to prod



#### Jenkins Managed Files
custom-settings.xml - Any specific maven settings configurations
docker_config.json  - Docker-format for AuthNZ, used by OCP to copy images between registries

#### Kubernetes Pod Template Environment Variables
$ARTIFACTORY_URL - points to the external image registry, should probably rename this to something vendor agnostic, note that this has a suffix of `/cicd` so that all images go to a common place

#### Jenkins Global Environment Variables
$OCPDEV_REGISTRY_URL  - points to the OCP DEV registry


## Plugins
- list of plugins here....
