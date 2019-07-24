
package com.steve.ocp

def process(def params) {

  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"
  def roller = load "src/com/steve/ocp/util/RolloutUtil.groovy"
  def envUtil = load "src/com/steve/ocp/util/EnvUtil.groovy"

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
  artifactName = "${pom.name}"
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
			stage('OCP Upload Binary') {
				sh """mkdir -p target/ocptarget/.s2i && find -type f \\( -iname '*.jar' -not -iname '*-sources.jar' \\) -exec mv {} target/ocptarget/${artifactName}.jar \\; && printf "GIT_REF=${ocpConfig.gitDigest}\nGIT_URL=${params.gitUrl}" > target/ocptarget/.s2i/environment"""
				bc.startBuild("--from-dir=target/ocptarget")
				bc.logs("-f")
			}

      stage ('Verify Build') {
				def builds = bc.related('builds')
				builds.watch {
					if ( it.count() == 0 ) return false
					// A robust script should not assume that only one build has been created, so we will need to iterate through all builds.
					def allDone = true
					it.withEach {
						// 'it' is now bound to a Selector selecting a single object for this iteration.  Let's model it in Groovy to check its status.
						def buildModel = it.object()
						if ( it.object().status.phase != "Complete" ) {
							allDone = false
						}
					}
					return allDone;
				}

        // time-based because Jenkins is running ephmerally, so current build number can be reset
        def current_image_tag = "${ocpConfig.projectName}:${artifactVersion}.t${currentBuild.startTimeInMillis}"
				openshift.tag("${ocpConfig.projectName}:latest", current_image_tag)
			}

      stage ('Verify DEV Deploy') {
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

      stage("Verify Rollout") {
        openshift.raw("tag ${params.containerRegistry}/cicd/${params.image} ${params.image}")
        openshift.raw("import-image ${params.image} --confirm ${params.containerRegistry}/cicd/${params.image} --insecure")
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


return this
