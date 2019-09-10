
package com.steve.ocp

// builds an image
def process(def params) {

  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"
  def roller = load "src/com/steve/ocp/util/RolloutUtil.groovy"
  def envUtil = load "src/com/steve/ocp/util/EnvUtil.groovy"
  def buildUtil = load "src/com/steve/ocp/util/BuildUtil.groovy"
  def gitter = load "src/com/steve/ocp/util/GitUtil.groovy"

  // apply labels to our secret so they sync with Jenkins
  openshift.withCluster() {
    openshift.withProject() {
      openshift.raw("label secret ${params.gitSA} credential.sync.jenkins.openshift.io=true --overwrite")

      stage('Checkout') {
        gitter.checkout(params.gitUrl, params.gitBranch, "${openshift.project()}-${params.gitSA}")
      }

      pom = readMavenPom file: 'pom.xml'

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

      stage('Sonar Scan') {
        if(p) {
          sh "mvn sonar:sonar -DskipTests $p -P coverage"
        }
        else {
          echo "Sonar scans skipped"
        }
      }

      stage('Package') {
        sh "mvn -Dmaven.test.failure.ignore package -DskipTests $p"
      }

      stage("Process CM/SK") {
        envUtil.resetEnvs(ocpConfig.projectName)

        Object data = fileLoader.readConfigMap("ocp/dev/${ocpConfig.configMapRef}.yml")
        envUtil.processCM(ocpConfig.configMapRef, ocpConfig.projectName, data)

        data = fileLoader.readSecret("ocp/dev/${ocpConfig.secretKeyRef}.yml")
        envUtil.processSK(ocpConfig.secretKeyRef, ocpConfig.projectName, data)
      }

			stage('Build Image') {
        def envmap = ["GIT_REF": gitter.digest(), "GIT_URL": params.gitUrl]
        buildUtil.start(ocpConfig.projectName, pom.artifactId, envmap)
			}

      stage ('Verify Build') {
				buildUtil.verify(ocpConfig.projectName)
			}

      stage ('Tag latest image') {
        // time-based because Jenkins is running ephmerally, so current build number can be reset
        def current_image_tag = "${ocpConfig.projectName}:${pom.version}.t${currentBuild.startTimeInMillis}"
        openshift.tag("${ocpConfig.projectName}:latest", current_image_tag)
      }

      stage("Verify Rollout") {
        roller.rollout(ocpConfig)
      }

    } // end withProject
  } //end withCluster
} // end def process


// Takes the "deploy" tag from Artifactory and rolls it out into QA
def release(def params) {

  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"
  def roller = load "src/com/steve/ocp/util/RolloutUtil.groovy"
  def envUtil = load "src/com/steve/ocp/util/EnvUtil.groovy"

  openshift.withCluster() {
    openshift.withProject() {

      // apply labels to our secret so they sync with Jenkins
      openshift.raw("label secret ${params.gitSA} credential.sync.jenkins.openshift.io=true --overwrite")

      def image = "${params.projectName}:${params.imageTag}"

      stage('Get Configs from SCM') {
        def gitter = load "src/com/steve/ocp/util/GitUtil.groovy"
        gitter.checkoutFromImage("${params.containerRegistry}/cicd/$image", "${openshift.project()}-${params.gitSA}")
      }

      ocpConfig = fileLoader.readConfig("./ocp/config.yml")
      ocpConfig << params

      stage("Process CM/SK") {
        envUtil.resetEnvs(ocpConfig.projectName)

        Object data = fileLoader.readConfigMap("ocp/qa/${ocpConfig.configMapRef}.yml")
        envUtil.processCM(ocpConfig.configMapRef, ocpConfig.projectName, data)

        data = fileLoader.readSecret("ocp/qa/${ocpConfig.secretKeyRef}.yml")
        envUtil.processSK(ocpConfig.secretKeyRef, ocpConfig.projectName, data)
      }

      stage("Pull latest image") {
        openshift.raw("import-image $image --confirm ${params.containerRegistry}/cicd/$image --insecure")
      }

      stage("Verify Rollout") {
        roller.rollout(ocpConfig)
  		}

    } // end withProject
  } // end withCluster
} // end def release

// takes the latest deployed image from QA and tags it with its POM version in Artifactory
def promote(def params) {

  openshift.withCluster() {
    openshift.withProject() {

      // apply labels to our secret so they sync with Jenkins
      openshift.raw("label secret ${params.gitSA} credential.sync.jenkins.openshift.io=true --overwrite")
      openshift.raw("label secret ${params.containerRegistryApiKey} credential.sync.jenkins.openshift.io=true --overwrite")

      def userInput = true
      def timeoutRejected = false
      def image = "${params.projectName}:${params.imageTag}"

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
          def artifactoryUtil = load "src/com/steve/ocp/util/ArtifactoryUtil.groovy"

          gitter.checkoutFromImage("${params.containerRegistry}/cicd/$image", "${openshift.project()}-${params.gitSA}")

          pom = readMavenPom file: 'pom.xml'
          artifactoryUtil.tag( "${openshift.project()}-${params.containerRegistryApiKey}", params.containerRegistry, image, pom.version )

          echo "this was successful"

       } else {
           // do something else
           echo "this was not successful"
           currentBuild.result = 'FAILURE'
       }

     } // end withProject
  } // end withCluster
} // end def promote

// Pulls image from Artifactory and rolls it out in production
def production(def params) {

  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"
  def roller = load "src/com/steve/ocp/util/RolloutUtil.groovy"
  def envUtil = load "src/com/steve/ocp/util/EnvUtil.groovy"
  def artifactoryUtil = load "src/com/steve/ocp/util/ArtifactoryUtil.groovy"

  openshift.withCluster() {
    openshift.withProject() {

      // apply labels to our secret so they sync with Jenkins
      openshift.raw("label secret ${params.gitSA} credential.sync.jenkins.openshift.io=true --overwrite")
      openshift.raw("label secret ${params.containerRegistryApiKey} credential.sync.jenkins.openshift.io=true --overwrite")

      def image = "${params.projectName}:${params.imageTag}"

      stage('Get Configs from SCM') {
        def gitter = load "src/com/steve/ocp/util/GitUtil.groovy"
        gitter.checkoutFromImage("${params.containerRegistry}/cicd/$image", "${openshift.project()}-${params.gitSA}")
      }

      ocpConfig = fileLoader.readConfig("./ocp/config.yml")
      ocpConfig << params

      stage("Process CM/SK") {
        envUtil.resetEnvs(ocpConfig.projectName)

        Object data = fileLoader.readConfigMap("ocp/prod/${ocpConfig.configMapRef}.yml")
        envUtil.processCM(ocpConfig.configMapRef, ocpConfig.projectName, data)

        data = fileLoader.readSecret("ocp/prod/${ocpConfig.secretKeyRef}.yml")
        envUtil.processSK(ocpConfig.secretKeyRef, ocpConfig.projectName, data)
      }

      def latestTag

      stage("Update Image Stream Tags") {
        def json = artifactoryUtil.getTags("${openshift.project()}-${params.containerRegistryApiKey}", params.containerRegistry, params.projectName)
        json.results.each {
            def parts = it.path.split "/"
            // The first one is the newest tag
            if(latestTag == null) {
              latestTag = parts[2]
            }
            openshift.raw ("tag --source=docker ${params.containerRegistry}/${parts[0]}/${parts[1]}:${parts[2]} ${parts[1]}:${parts[2]}")
        }
      }

      stage("Verify Rollout") {
        openshift.raw("""patch dc ${ocpConfig.projectName} --patch='{"spec":{"template":{"spec":{"containers":[{"name": "${params.projectName}", "image":"docker-registry.default.svc:5000/${openshift.project()}/${params.projectName}:$latestTag"}]}}}}'""")
        roller.rollout(ocpConfig)
      }

    } // end withProject
  } // end withCluster
} // end def production

return this
