package com.steve.ocp

def process(def params) {

	def dev_image_tag = "${params.projectName}:${params.selectedImageTag}"
	def qa_image_tag = "${dev_image_tag}-rc-b${currentBuild.number}"

	openshift.withCluster() {
      stage('Push into Artifactory') {
        configFileProvider([configFile(fileId: 'docker_config.json', targetLocation: "/home/jenkins/.docker/config.json")]) {
          // the ARTIFACTORY_URL also includes the path to store the image in Artifactory

          def results = openshift.raw("image mirror $OCPDEV_REGISTRY_URL/${params.ocpnamespace}/$dev_image_tag $ARTIFACTORY_URL/$qa_image_tag --insecure ")
          echo "image mirror results = $results"
        }
      }

      stage('Get Configs from SCM') {
				// the image has a reference to the git commit's SHA
        def image_info = openshift.raw("image info $ARTIFACTORY_URL/$qa_image_tag --insecure")
				def commitHash = (image_info =~ /GIT_REF=[a-z0-9]+/)[0].split("=")[1] ?: ""

				if(commitHash != "") {
					// not good, but necessary until we fix our self-signed certificate issue
					try {
						checkout([$class: 'GitSCM', branches: [[name: commitHash ]], userRemoteConfigs: [[credentialsId: 'git-sa', url: params.gitUrl]]])
					} catch (Exception e) {
						println e
						sh "git config http.sslVerify false"
						checkout([$class: 'GitSCM', branches: [[name: commitHash ]], userRemoteConfigs: [[credentialsId: 'git-sa', url: params.gitUrl]]])
					}
				}

      }

			stage("Process Templates") {

			}

			stage("Process CM/SK") {

			}

			stage("Verify Rollout") {

			}

	} // end withCluster
} //end runPipeline
