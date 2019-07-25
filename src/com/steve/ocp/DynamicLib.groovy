
package com.steve.ocp

def process(def params) {

  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"
  def roller = load "src/com/steve/ocp/util/RolloutUtil.groovy"
  def envUtil = load "src/com/steve/ocp/util/EnvUtil.groovy"
  def buildUtil = load "src/com/steve/ocp/util/BuildUtil.groovy"

  // apply labels to our secret so they sync with Jenkins
  openshift.withCluster() {
    openshift.withProject() {
      openshift.raw("label secret ${params.gitSA} credential.sync.jenkins.openshift.io=true --overwrite")

      stage('Checkout') {
        def gitter = load "src/com/steve/ocp/util/GitUtil.groovy"
        gitter.checkout(params.gitUrl, params.gitBranch, "${openshift.project()}-${params.gitSA}")
        params['gitDigest'] = gitter.digest()
      }
    }
  }

  pom = readMavenPom file: 'pom.xml'
  artifactName = "${pom.artifactId}"
  artifactVersion = "${pom.version}"

  ocpConfig = fileLoader.readConfig("./ocp/config.yml")
  // load in the Jenkins parameters into the configuration object so we have everything in one place
	ocpConfig << params

  def profile = pom.profiles.find { it.id == "ocp" }
  def p = profile ? "-P ${profile.id}" : ""

  stage('Build') {
    sh "mvn clean compile -DskipTests $p"
  }

  stage('Test') {
    sh "mvn test $p"
  }

  stage('Package') {
    sh "mvn -Dmaven.test.failure.ignore package -DskipTests $p"
  }


  openshift.withCluster() {
    openshift.withProject() {

      stage("Process CM/SK") {
        Object data = fileLoader.readConfigMap("ocp/dev/${ocpConfig.configMapRef}.yml")
        envUtil.processCM(ocpConfig.configMapRef, ocpConfig.projectName, data)

        data = fileLoader.readSecret("ocp/dev/${ocpConfig.secretKeyRef}.yml")
        envUtil.processSK(ocpConfig.secretKeyRef, ocpConfig.projectName, data)
      }

      def bc = openshift.selector("buildconfig", "${ocpConfig.projectName}")
			stage('Build Image') {
        def envmap = ["GIT_REF": ocpConfig.gitDigest, "GIT_URL": params.gitUrl]
        buildUtil.start(ocpConfig.projectName, artifactName, envmap)
			}

      stage ('Verify Build') {
				buildUtil.verify(ocpConfig.projectName)
			}

      stage ('Tag latest image') {
        // time-based because Jenkins is running ephmerally, so current build number can be reset
        def current_image_tag = "${ocpConfig.projectName}:${artifactVersion}.t${currentBuild.startTimeInMillis}"
        openshift.tag("${ocpConfig.projectName}:latest", current_image_tag)
      }

      stage("Verify Rollout") {
        roller.rollout(ocpConfig.projectName, ocpConfig.replicas)
      }

      echo "Hello from project ${openshift.project()} in cluster ${openshift.cluster()} with params $ocpConfig"

    } // end withProject
  } //end withCluster
} // end def process


def release(def params) {

  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"
  def roller = load "src/com/steve/ocp/util/RolloutUtil.groovy"
  def envUtil = load "src/com/steve/ocp/util/EnvUtil.groovy"

  openshift.withCluster() {
    openshift.withProject() {

      // apply labels to our secret so they sync with Jenkins
      openshift.raw("label secret ${params.gitSA} credential.sync.jenkins.openshift.io=true --overwrite")

      stage('Get Configs from SCM') {
        def gitter = load "src/com/steve/ocp/util/GitUtil.groovy"
        gitter.checkoutFromImage("${params.containerRegistry}/cicd/${params.image}", "${openshift.project()}-${params.gitSA}")
      }

      ocpConfig = fileLoader.readConfig("./ocp/config.yml")
      ocpConfig << params

      stage("Process CM/SK") {
        Object data = fileLoader.readConfigMap("ocp/qa/${ocpConfig.configMapRef}.yml")
        envUtil.processCM(ocpConfig.configMapRef, ocpConfig.projectName, data)

        data = fileLoader.readSecret("ocp/qa/${ocpConfig.secretKeyRef}.yml")
        envUtil.processSK(ocpConfig.secretKeyRef, ocpConfig.projectName, data)
      }

      stage("Pull latest image") {
        openshift.raw("tag ${params.containerRegistry}/cicd/${params.image} ${params.image}")
        openshift.raw("import-image ${params.image} --confirm ${params.containerRegistry}/cicd/${params.image} --insecure")
      }

      stage("Verify Rollout") {
        roller.rollout(ocpConfig.projectName, ocpConfig.replicas)
  		}


    } // end withProject
  } // end withCluster
} // end def release

def promote(def params) {

  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"

  openshift.withCluster() {
    openshift.withProject() {

      // apply labels to our secret so they sync with Jenkins
      openshift.raw("label secret ${params.gitSA} credential.sync.jenkins.openshift.io=true --overwrite")
      openshift.raw("label secret ${params.containerRegistryApiKey} credential.sync.jenkins.openshift.io=true --overwrite")

      def userInput = true
      def timeoutRejected = false

      try {
         stage("Approval") {
             timeout(time: 15, unit: 'SECONDS') { // change to a convenient timeout for you
                 userInput = input(
                 id: 'Proceed1', message: 'Was this successful?', parameters: [
                 [$class: 'BooleanParameterDefinition', defaultValue: true, description: '', name: 'Please confirm you agree with this']
                 ])
             }
         }
      } catch(err) { // timeout reached or input false
         timeoutRejected = true
         currentBuild.result = 'FAILURE'
      }

      if (timeoutRejected) {
           // we timed out or user rejected input
           echo "timedout or rejected by user"
       } else if (userInput) {

          def gitter = load "src/com/steve/ocp/util/GitUtil.groovy"
          gitter.checkoutFromImage("${params.containerRegistry}/cicd/${params.image}", "${openshift.project()}-${params.gitSA}")

          pom = readMavenPom file: 'pom.xml'
          artifactVersion = "${pom.version}"

          withCredentials([string(credentialsId: "${openshift.project()}-${params.containerRegistryApiKey}", variable: 'APIKEY')]) {

            def img = "${params.image}".split(":")[0]
            def tag = "${params.image}".split(":")[1]

           sh """
          curl -k -X POST '${params.containerRegistry}/artifactory/api/docker/docker-release-local/v2/promote' \
            -H 'cache-control: no-cache' \
            -H 'content-type: application/json' \
            -H 'x-jfrog-art-api: $APIKEY' \
            -d '{ "targetRepo" : "docker-release-local",
                  "dockerRepository" : "cicd/$img",
                  "tag": "$tag",
                  "targetTag": "$artifactVersion",
                  "copy" : true}'
               """
         }

           echo "this was successful"

       } else {
           // do something else
           echo "this was not successful"
           currentBuild.result = 'FAILURE'
       }

     }
  }
}


def production(def params) {

  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"
  def roller = load "src/com/steve/ocp/util/RolloutUtil.groovy"
  def envUtil = load "src/com/steve/ocp/util/EnvUtil.groovy"

  openshift.withCluster() {
    openshift.withProject() {

      // apply labels to our secret so they sync with Jenkins
      openshift.raw("label secret ${params.gitSA} credential.sync.jenkins.openshift.io=true --overwrite")
      openshift.raw("label secret ${params.containerRegistryApiKey} credential.sync.jenkins.openshift.io=true --overwrite")

      stage('Get Configs from SCM') {
        def gitter = load "src/com/steve/ocp/util/GitUtil.groovy"
        gitter.checkoutFromImage("${params.containerRegistry}/cicd/${params.image}", "${openshift.project()}-${params.gitSA}")
      }

      ocpConfig = fileLoader.readConfig("./ocp/config.yml")
      ocpConfig << params

      stage("Process CM/SK") {
        Object data = fileLoader.readConfigMap("ocp/prod/${ocpConfig.configMapRef}.yml")
        envUtil.processCM(ocpConfig.configMapRef, ocpConfig.projectName, data)

        data = fileLoader.readSecret("ocp/prod/${ocpConfig.secretKeyRef}.yml")
        envUtil.processSK(ocpConfig.secretKeyRef, ocpConfig.projectName, data)
      }

      def latestTag

      stage("Update Image Stream Tags") {

        withCredentials([string(credentialsId: "${openshift.project()}-${params.containerRegistryApiKey}", variable: 'APIKEY')]) {
          def curl = """curl -k -X POST ${params.containerRegistry}/artifactory/api/search/aql \
          -H 'cache-control: no-cache' \
          -H 'content-type: text/plain' \
          -H 'x-jfrog-art-api: $APIKEY' \
          -d 'items.find({"repo": {"\$eq": "docker-release-local"}, "path": {"\$match": "cicd/${params.projectName}/*"},"name": {"\$eq": "manifest.json"}}).include("repo", "path", "name", "updated").sort ({ "\$desc": ["updated"] } )' """
          println curl
          curl = sh (returnStdout: true, script: curl)
          def json = readJSON text: curl

          json.results.each {
              def parts = it.path.split "/"

              // The first one is the newest tag
              if(latestTag == null) {
                latestTag = parts[2]
              }

              openshift.raw ("tag --source=docker ${params.containerRegistry}/${parts[0]}/${parts[1]}:${parts[2]} ${parts[1]}:${parts[2]}")
          }
        }

      }

      stage("Verify Rollout") {
        openshift.raw("""patch dc ${ocpConfig.projectName} --patch='{"spec":{"template":{"spec":{"containers":[{"name": "${params.projectName}", "image":"docker-registry.default.svc:5000/${openshift.project()}/${params.projectName}:$latestTag"}]}}}}'""")
        roller.rollout(ocpConfig.projectName, ocpConfig.replicas)
      }

    } // end withProject
  } // end withCluster
} // end def production

return this
