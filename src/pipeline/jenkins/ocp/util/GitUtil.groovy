package pipeline.jenkins.ocp.util

def checkout(def url, def branch, def credentials) {
  try {
    checkout([$class: 'GitSCM', branches: [[name: branch ]], userRemoteConfigs: [[credentialsId: credentials, url: url]]])
  } catch (Exception e) {
    // not good, but necessary if we have untrusted self-signed certificates
    sh "git config http.sslVerify false"
    checkout([$class: 'GitSCM', branches: [[name: branch ]], userRemoteConfigs: [[credentialsId: credentials, url: url]]])
  }
}

def checkoutFromImage(def image, def credentials) {

  def image_info = openshift.raw("image info $image --insecure")
  def commitHash = (image_info =~ /GIT_REF=[\w*-]+/)[0].split("=")[1] ?: ""
  def gitRepo    = (image_info =~ /GIT_URL=[\w*-:]+/)[0].split("=")[1] ?: ""

  if(commitHash != "") {
    checkout(gitRepo, commitHash, credentials)
  }
  else {
    error("Failing the build because we could not find an a GIT reference in the image")
  }
}


def digest() {
  return sh(script: "git rev-parse HEAD", returnStdout: true).trim()
}

return this
