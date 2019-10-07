package pipeline.jenkins.ocp.util

// image is the format imageName:tagName
def tag(def credentials, def registry, def image, def destTag) {

  withCredentials([string(credentialsId: credentials, variable: 'APIKEY')]) {
    def img = image.split(":")[0]
    def tag = image.split(":")[1]

    sh """curl -k -X POST '$registry/artifactory/api/docker/docker-release-local/v2/promote' \
    -H 'cache-control: no-cache' \
    -H 'content-type: application/json' \
    -H 'x-jfrog-art-api: $APIKEY' \
    -d '{ "targetRepo" : "docker-release-local",
          "dockerRepository" : "cicd/$img",
          "tag": "$tag",
          "targetTag": "$destTag",
          "copy" : true}' """
  }

}

def getTags(def credentials, def registry, def image) {

  withCredentials([string(credentialsId: credentials, variable: 'APIKEY')]) {
    def curl = """curl -k -X POST $registry/artifactory/api/search/aql \
    -H 'cache-control: no-cache' \
    -H 'content-type: text/plain' \
    -H 'x-jfrog-art-api: $APIKEY' \
    -d 'items.find({"repo": {"\$eq": "docker-release-local"}, "path": {"\$match": "cicd/$image/*"},"name": {"\$eq": "manifest.json"}}).include("repo", "path", "name", "updated").sort ({ "\$desc": ["updated"] } )' """
    //println curl
    curl = sh (returnStdout: true, script: curl)
    def json = readJSON text: curl

    return json
  }
}

return this
