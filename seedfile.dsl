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

pipelineJob("${pipelineId}-release") {

  parameters {
    activeChoiceParam('ENVS') {
      description('Choose environment to deploy to')
      choiceType('SINGLE_SELECT')
      groovyScript {
        script('return ["QA", "PROD"]')
        fallbackScript('return ["error"]')
      }
    }

    activeChoiceReactiveParam('IMAGE_TAG') {
        description('Select an Image Tag to Promote')
        choiceType('SINGLE_SELECT')
        referencedParameter('ENVS')
        groovyScript {
            script(
"""import groovy.json.JsonSlurper
import jenkins.model.*

if (ENVS.equals("QA")) {
  try {
    def creds = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(com.cloudbees.plugins.credentials.Credentials.class, Jenkins.instance, null, null);
    def token = creds.find {it.id == 'jenkins-sa-token'}
    def curl = [ 'bash', '-c', "curl -k https://\$OCPDEV_REGISTRY_URL/v2/$OCP_NAMESPACE/$PROJECT_NAME/tags/list -u any:\${token.getSecret().getPlainText()}"]
    return new JsonSlurper().parseText(curl.execute().text).tags
  } catch (Exception e) {
      println(e)
  }

} else if (ENVS.equals("PROD")) {
  return ["nothing yet"]
}""")
            fallbackScript('return ["error"]')
        } // end groovyScript
    }  // end activeChoiceParam
  } // end parameters

  definition {
    cps {
      script(
"""
node {
  sh "echo 'hello world'"
}"""
      )
      sandbox()
      concurrentBuild(false)
    }

    description("This is an auto-generated pipeline")
  }

}
