# pipeline-bootstrap

This is an example of an ephemeral pipeline deployed in Openshift.  It uses an Openshift `Template` to create a `DeploymentConfig`, `Service`, `ImageStream`, and `BuildConfigs`.

The goal of this project is to quickly deploy applications into openshift, without the need of persistent storage.  The entire pipeline can be recreated from source with very little manual intervention.

This project also heavily leverages a `BuildConfig` with the `jenkinsPipelineStrategy` build strategy, so that pipelines can be kicked off from the Openshift GUI.  Users are abstracted from the fact that Jenkins is the underlying tool for building and deploying code.

### Templates
The templates are broken out into three files, which is influenced by a three environment software development lifecycle.

- jenkinsstrategy-java-pipelie-dev.yml
- jenkinsstrategy-java-pipelie-qa.yml
- jenkinsstrategy-java-pipelie-prod.yml

The **dev** template does the actual building of the container image, and is responsible for compiling, testing, and packaging the code.  It also creates a copy of the image and pushes it into an external registry.  The goal of **dev** pipeline is to create release candidates, which are allowed to go into the next stage of the SDLC.

The **qa** template takes the image marked as a release candidate and deploys it into a Quality Assurance testing area.  It does so by looking for an image with a tag named **deploy**.  When tested, and accepted by the QA team, there is a promotion pipeline that will tag the **deploy** image with the `pom.version`, which is specified by the `pom.xml` of the project.  The tagged image is then pushed into an external registry

The **prod** template creates a pipeline that deploys the latest/newest image tagged in the external registry.

### Warnings

This pipeline uses an ephemeral instance of Jenkins.  At any point, the Jenkins pod may be terminated, so all internal counters or variables are reset.  Any customization of Jenkins through the administrative console will be lost on redeployment of the pod.

This workflow is also very static, and makes assumptions that prerequisite steps were performed.  It would be very easy to break the workflow if there is any deviation of the DEV -> QA -> PROD process, especially when features are being developed concurrently.

Images in the external image registry can be overwritten by the pipeline.  The pipeline does not perform any validation to ensure that only new tags are created.  This means already-published-images can be overwritten, and all history is lost!

### Installation and Configuration

- The templates are loaded into the `openshift` namespace so they are globally available from existing namespaces.  Each template is environment specific, so you only need to load one per environment.

- In the DEV environment, the openshift3/jenkins-1-rhel7:latest imagestream is required in the `openshift` namespace.
  ```bash
  oc import-image openshift3/jenkins-agent-maven-35-rhel7:v3.11 --confirm --from {your_registry}/openshift3/jenkins-agent-maven-35-rhel7:v3.11 -n openshift
  ```

- In QA and PROD, the openshift4/ose-jenkins-agent-maven:latest is required in the `openshift` namespace. *(The pipeline leverages newer features with the openshift `cli` tool)*   
  ```bash
  oc import-image ose-jenkins-agent-maven:latest --confirm --from {your_registry}/openshift4/ose-jenkins-agent-maven:latest -n openshift
  ```

- Import the actual pipeline manifest with the following.
  ```bash
  oc create -f resources/pipeline/jenkins/jenkinsstrategy-java-pipeline-{dev,qa,prod}.yml -n openshift
  ```

### Onboarding Prerequisites

- Namespaces must be created ahead of time. The templates are instantiated into a specific namespace

- The project administrator must create an openshift `Secret` if authentication is required to access the Git project where the source code is located.  The template uses a default name of **cicd-secret**

- The project administrator must create an openshift `Secret` if authentication is required to access the External Image Registry where build images will be pushed into.  The template uses a default name of **artifactory-secret**

  - **DEV** : the External Image Registry credentials must be configured as an `Image Secret` with username/password
  - **QA/PROD** : the External Image Registry credentials must be configured as a `Generic Secret` with its key named `secrettext`.  The value of this field is the JFrog API Key (this is Artifactory specific, but may change)

- See [Project Specific Prerequisites](README_APPS.md) for additional information about how the application needs to be configured.

### Image Tagging

Images are tagged with the following strategy.

In DEV, images are pushed into Openshift's internal image registry.  It always retags the last built image as `latest`, and creates an additional tag with convention `{pom.version}.t{currentBuild.startTimeInMillis}`

When moving to QA, the `latest` tag is pushed into the external image registry and retagged with a `deploy` tag.

When promoting to PROD, the `deploy` tag is tagged again with only its `{pom.version}`

| DEV  | QA | PROD  |
|:-:|:-:|:-:|
| :latest <br/> :{pom.version}.t{currentBuild.startTimeInMillis}  | :deploy  | :latest <br/> :{pom.version}  |

All tags going to the external image registry are stored in an subdirectory named `/cicd`

#### Kubernetes Pod Template Configuration
The idle time of the pods is set to 10 minutes, so that subsequent builds/deploys can reuse an existing pod.  This is done for efficiency so repeated builds do not need to wait for pod recreation.

- OPENSHIFT_JENKINS_JVM_ARCH - set to `x86_64`


### Developer Tips
- Developers who prefer rapid development, or those who wish to test their code before checking into source control can leverage Openshift's binary `BuildConfig` from their local machines.  [See official documentation here](https://docs.openshift.com/container-platform/3.11/dev_guide/dev_tutorials/binary_builds.html)
- If you need to clean up all the generated objects, perhaps you want to rerun the template, you can run the following to find all the associated objects and delete them.
    - Example
    ```bash
    oc get all,configmap,secret -l app={your_app_name}
    ```
