
package com.steve.ocp


def process(def params) {

  // this works
  def fileLoader = load "src/com/steve/ocp/util/FileLoader.groovy"

  // requires scripts approval, which we don't want to deal with with ephemeral jenkins
  //evaluate(new File("src/com/steve/ocp/util/FileLoader.groovy"))

  stage('Checkout') {
    // not good, but necessary until we fix our self-signed certificate issue
    try {
      git branch: "${params.gitBranch}", credentialsId: 'git-sa', url: "${params.gitUrl}"
    } catch (Exception e) {
      sh "git config http.sslVerify false"
      git branch: "${params.gitBranch}", credentialsId: 'git-sa', url: "${params.gitUrl}"
    }

    params['gitDigest'] = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
  }

  pom = readMavenPom file: 'pom.xml'
  artifactName = "${pom.name}"
  artifactVersion = "${pom.version}"

  ocpConfig = fileLoader.readConfig("./ocp/config.yml")
  // load in the Jenkins parameters into the configuration object so we have everything in one place
	ocpConfig << params

  stage('Build') {
    sh "mvn clean compile -DskipTests"
  }

  stage('Test') {
    sh "mvn test"
  }

  stage('Package') {
    sh "mvn -Dmaven.test.failure.ignore package -DskipTests"
  }


  openshift.withCluster() {
    openshift.withProject("steve-test1") {

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

      def bc = openshift.selector("buildconfig", "${ocpConfig.projectName}")
			stage('OCP Upload Binary') {
				sh """mkdir -p target/ocptarget/.s2i && find -type f \\( -iname '*.jar' -not -iname '*-sources.jar' \\) -exec mv {} target/ocptarget/${artifactName}.jar \\; && echo "GIT_REF=${ocpConfig.gitDigest}" > target/ocptarget/.s2i/environment"""
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
    }
  }

} // end def

return this
