def pipelineId = "${ PIPELINE_NAME ?: PROJECT_NAME }"

pipelineJob(pipelineId) {
  definition {
    cps {
      script(
"""@Library('PipelineBootstrap@master') _

node {
  def params = ['projectName' : "$PROJECT_NAME", 'ocpnamespace' : "$OCP_NAMESPACE", 'gitBranch': "$GIT_BRANCH", 'gitUrl': "$GIT_URL"]
  new com.steve.ocp.PipelineLoader().runPipeline(params)
}"""
      )
      sandbox()
      concurrentBuild(false)
    }

    description("This is an auto-generated pipeline\nBuilt from $GIT_BRANCH on $GIT_URL")
  }
}
