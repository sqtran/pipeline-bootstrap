
package pipeline.jenkins.ocp

def initialize(def params) {

  openshift.withCluster() {
    openshift.withProject() {
      stage("Apply Pod Configs") {
        def template = "resources/pipeline/jenkins/jenkinsPodTemplate.yml"
        if (params?.workflow?.trim() != "build") {
          template = "resources/pipeline/jenkins/jenkinsPodTemplate4x.yml"
        }
        openshift.raw("apply -f $template")
      }
    }
  }

  stage("Wait for Pod") {
    node("maven") {
      // load scripts again, but this time on maven node
      git url: "https://github.com/sqtran/pipeline-bootstrap", branch: "${params.workflowBranch}"
      def code = load 'src/pipeline/jenkins/ocp/DynamicLib.groovy'

      switch (params?.workflow?.trim()) {
        case ~/build/:
          code.process(params)
          break
        case ~/release/:
          code.release(params)
          break
        case ~/promote/:
          code.promote(params)
          break
        case ~/production/:
          code.production(params)
          break
        default:
          throw new RuntimeException("Invalid workflow specified '${params.workflow}'")
          break
      }
    }
  }
}

return this
