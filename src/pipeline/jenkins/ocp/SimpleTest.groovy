def printHello() {

println "Hello World from New Class!"


  node {
    println "Hello World from inside node block"

    stage("Steve 1") {
      println "Hello World from inside stage block"
    }
  }


}
