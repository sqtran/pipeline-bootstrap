package com.steve.ocp

def process(def params) {

	ocHome  = tool 'oc311'
	ocHome  = "$ocHome/openshift-origin-client-tools-v3.11.0-0cbc58b-linux-64bit"
	oc      = "$ocHome/oc"

  // TODO checkout by GIT_REF
	// TODO get POM info
  // TODO load Config Files
  // TODO Switch to QA or PROD

	openshift.withCluster() {
		withEnv(["PATH+OC=$ocHome"]) {
      stage('Push into Artifactory') {
        configFileProvider([configFile(fileId: 'docker_config.json', targetLocation: "/home/jenkins/.docker/config.json")]) {
          // the ARTIFACTORY_URL also includes the path to store the image in Artifactory

          def current_image_tag = "${params.projectName}:${params.selectedImageTag}"

          def results = openshift.raw("image mirror $OCPDEV_REGISTRY_URL/${params.ocpnamespace}/$current_image_tag $ARTIFACTORY_URL/$current_image_tag-rc-b${currentBuild.number} --insecure ")
          echo "image mirror results = $results"
        }
      }
      // TODO create Service, Route, Deployment Config, Image Stream
      // TODO process CM/SK
      // TODO Verify Deployment

		} // end withEnv
	} // end withCluster
} //end runPipeline
