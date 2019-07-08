package com.steve.ocp

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

import com.steve.ocp.util.FileLoader
import com.steve.ocp.util.Sanitizer
import com.steve.ocp.util.TemplateProcessor
import com.steve.ocp.util.ConfigMapProcessor

def stage(name, execute, block) {
    return stage(name, execute ? block : {
        echo "skipped stage $name"
        Utils.markStageSkippedForConditional(STAGE_NAME)
    })
}

def process(def params) {

	def fileLoader = new FileLoader()


  // we sanitize because we assume all input is valid from here on out
  params = new Sanitizer().sanitizePipelineInput(params)

	stage('Checkout') {
    // not good, but necessary until we fix our self-signed certificate issue
    //sh "git config http.sslVerify false"
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

	ocpConfig = fileLoader.readConfig("$WORKSPACE/ocp/config.yml")

	// load in the Jenkins parameters into the configuration object so we have everything in one place
	ocpConfig << params

  if (!isUnix()) {
    error('Failing build because this is not a slave linux container... who knows what will happen')
  }

  configFileProvider([configFile(fileId: 'custom-settings.xml', variable: 'MAVEN_SETTINGS')]) {
    stage('Build') {
  		sh "mvn -s $MAVEN_SETTINGS clean compile -DskipTests"
  	}

    stage('Test') {
      sh "mvn -s $MAVEN_SETTINGS test"
    }

    stage('Package') {
  		sh "mvn -s $MAVEN_SETTINGS -Dmaven.test.failure.ignore package -DskipTests"
  	}
  }

	openshift.withCluster() {

    stage('Process Templates') {
			new TemplateProcessor().processBuildTemplates(ocpConfig)
		}

		stage("Process CM/SK") {
      new ConfigMapProcessor().processCMSK(ocpConfig, "dev")
		}

		openshift.withProject("${ocpConfig.ocpnamespace}") {
			def bc = openshift.selector("buildconfig", "${ocpConfig.projectName}")

			stage('OCP Upload Binary') {
				sh "mkdir -p target/ocptarget/.s2i && mv target/${artifactName}.jar target/ocptarget && echo \"GIT_REF=${ocpConfig.gitDigest}\" > target/ocptarget/.s2i/environment"
				bc.startBuild("--from-dir=target/ocptarget")
				bc.logs("-f")
			}

      // Job ID is the first 8 characters of the UUID based on the JOB_NAME
      def jid = UUID.nameUUIDFromBytes("$JOB_NAME".getBytes()).toString().substring(0,8);
      def current_image_tag = "${ocpConfig.projectName}:${artifactVersion}-${jid}-b${currentBuild.number}"
      println "This Pipeline has Job ID $jid and will tag the newly created image as $current_image_tag"

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

		} // end withProject
	} // end withCluster
} //end process
