
package com.steve.ocp

def process(def params) {
  openshift.withCluster() {
    openshift.withProject("steve-test1") {
      echo "Hello from project ${openshift.project()} in cluster ${openshift.cluster()} with params $params"
    }
  }
}

return this
