def pipelineId = "${ PIPELINE_NAME ?: PROJECT_NAME }"

pipelineJob(pipelineId) {
  definition {
    cps {
      script(
"""@Library('PipelineBootstrap@master') _

node {
  def projectName = "$PROJECT_NAME"
  def gitProject = "$GIT_URL"
  def gitBranch = "$GIT_BRANCH"
  def ocpnamespace = "$OCP_NAMESPACE"

  def params = ['projectName' : projectName, 'ocpnamespace' : ocpnamespace]

  stage('Checkout') {
    // not good, but necessary until we fix our self-signed certificate issue
    //sh "git config http.sslVerify false"
    try {
      git branch: gitBranch, credentialsId: '6d8ed739-d67d-47f3-8194-c5f3f665da7d', url: gitProject
    } catch (Exception e) {
      sh "git config http.sslVerify false"
      git branch: gitBranch, credentialsId: '6d8ed739-d67d-47f3-8194-c5f3f665da7d', url: gitProject
    }

    params['gitDigest'] = sh(script: "git rev-parse HEAD", returnStdout: true).trim()
  }

  new com.steve.ocp.PipelineLoader().runPipeline(params)
}"""
      )
      sandbox()
      concurrentBuild(false)
    }

    description("This is an auto-generated pipeline")
  }
}
