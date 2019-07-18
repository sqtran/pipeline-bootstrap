
package com.steve.ocp

def process(def params) {

  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"

  // grab the current namespace we're running in
  def namespace = openshift.withCluster() { openshift.project() }

  stage('Checkout') {
    // not good, but necessary until we fix our self-signed certificate issue
    try {
      git branch: "${params.gitBranch}", credentialsId: "$namespace-${params.gitSA}", url: "${params.gitUrl}"
    } catch (Exception e) {
      sh "git config http.sslVerify false"
      git branch: "${params.gitBranch}", credentialsId: "$namespace-${params.gitSA}", url: "${params.gitUrl}"
    }

    params['gitDigest'] = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
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

      // Process Config Map
      Object data = fileLoader.readConfigMap("ocp/dev/${ocpConfig.configMapRef}.yml")
      data.metadata.labels['app'] = "${ocpConfig.projectName}"
      data.metadata.name = "${ocpConfig.configMapRef}"

      def prereqs = openshift.selector( "configmap", "${ocpConfig.configMapRef}" )
      if(!prereqs.exists()) {
        println "ConfigMap ${ocpConfig.configMapRef} doesn't exist, creating now"
        openshift.create(data)
      }
      else {
        println "ConfigMap ${ocpConfig.configMapRef} exists, updating now"
        openshift.apply(data)
      }

      try {
        openshift.raw("set env dc/${ocpConfig.projectName} --from configmap/${ocpConfig.configMapRef}")
      } catch (Exception e) {

      }

      // Process Secret
      data = fileLoader.readSecret("ocp/dev/${ocpConfig.secretKeyRef}.yml")
      data.metadata.labels['app'] = "${ocpConfig.projectName}"
      data.metadata.name = "${ocpConfig.secretKeyRef}"

      prereqs = openshift.selector( "secret", "${ocpConfig.secretKeyRef}" )
      if(!prereqs.exists()) {
        println "Secret ${ocpConfig.secretKeyRef} doesn't exist, creating now"
        openshift.create(data)
      }
      else {
        println "Secret ${ocpConfig.secretKeyRef} exists, updating now"
        openshift.apply(data)
      }

      try {
        openshift.raw("set env dc/${ocpConfig.projectName} --from secret/${ocpConfig.secretKeyRef}")
      } catch (Exception e) {

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
        // Scale if user had specified a specific number of replicas, otherwise just do whatever is already configured in OCP
        def desiredReplicas = ocpConfig.replicas
        if(desiredReplicas != null) {
          openshift.raw("scale deploymentconfig ${ocpConfig.projectName} --replicas=$desiredReplicas")
        }

        timeout(time: 2, unit: 'MINUTES') {
          openshift.selector('dc', ocpConfig.projectName).rollout().status()
        }
      } // end stage

      echo "Hello from project ${openshift.project()} in cluster ${openshift.cluster()} with params $ocpConfig"

    } // end withProject
  } //end withCluster
} // end def process


def release(def params) {

  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"

  openshift.withCluster() {
    openshift.withProject() {

      stage('Get Configs from SCM') {
  			// the image has a reference to the git commit's SHA
        def image_info = openshift.raw("image info ${params.image} --insecure")
  			def commitHash = (image_info =~ /GIT_REF=[\w*-]+/)[0].split("=")[1] ?: ""
        def gitRepo    = (image_info =~ /GIT_URL=[\w*-:]+/)[0].split("=")[1] ?: ""

  			if(commitHash != "") {
  				// not good, but necessary until we fix our self-signed certificate issue
  				try {
  					checkout([$class: 'GitSCM', branches: [[name: commitHash ]], userRemoteConfigs: [[credentialsId: "${openshift.project()}-${params.gitSA}", url: gitRepo]]])
  				} catch (Exception e) {
  					println e
  					sh "git config http.sslVerify false"
  					checkout([$class: 'GitSCM', branches: [[name: commitHash ]], userRemoteConfigs: [[credentialsId: "${openshift.project()}-${params.gitSA}", url: gitRepo]]])
  				}
  			}
      }

      ocpConfig = fileLoader.readConfig("./ocp/config.yml")


      stage("Process CMSK") {


              // Process Config Map
              Object data = fileLoader.readConfigMap("ocp/qa/${ocpConfig.configMapRef}.yml")
              data.metadata.labels['app'] = "${ocpConfig.projectName}"
              data.metadata.name = "${ocpConfig.configMapRef}"

              def prereqs = openshift.selector( "configmap", "${ocpConfig.configMapRef}" )
              if(!prereqs.exists()) {
                println "ConfigMap ${ocpConfig.configMapRef} doesn't exist, creating now"
                openshift.create(data)
              }
              else {
                println "ConfigMap ${ocpConfig.configMapRef} exists, updating now"
                openshift.apply(data)
              }

              try {
                openshift.raw("set env dc/${ocpConfig.projectName} --from configmap/${ocpConfig.configMapRef}")
              } catch (Exception e) {

              }

              // Process Secret
              data = fileLoader.readSecret("ocp/dev/${ocpConfig.secretKeyRef}.yml")
              data.metadata.labels['app'] = "${ocpConfig.projectName}"
              data.metadata.name = "${ocpConfig.secretKeyRef}"

              prereqs = openshift.selector( "secret", "${ocpConfig.secretKeyRef}" )
              if(!prereqs.exists()) {
                println "Secret ${ocpConfig.secretKeyRef} doesn't exist, creating now"
                openshift.create(data)
              }
              else {
                println "Secret ${ocpConfig.secretKeyRef} exists, updating now"
                openshift.apply(data)
              }

              try {
                openshift.raw("set env dc/${ocpConfig.projectName} --from secret/${ocpConfig.secretKeyRef}")
              } catch (Exception e) {

              }


      }



    } // end withProject
  } // end withCluster
} // end def release

return this
