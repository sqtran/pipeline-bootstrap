package com.steve.ocp.util

@Grab('org.yaml:snakeyaml:1.17')
import org.yaml.snakeyaml.Yaml
	
static def readConfig(def filePath = "$WORKSPACE/ocp/config.yml") {
	String[] fields = ["readinessProbe", "livelinessProbe", "secretKeyRef", "configMapRef"]
	def config = readFile(filePath, "A config.yml file is required")

	StringBuilder err = new StringBuilder("")
	fields.each {		
		if(config["${it}"] == null || config["${it}"] == "") {
			err.append("${it} is a required field\n")
		}
	}
	if(err.toString() != "") {
		throw new RuntimeException(err.toString())
	}
	return config
}

static def readConfigMap(def filePath) {
	return readFile(filePath, "A ConfigMap file is required")
}

static def readSecret(def filePath) {
	return readFile(filePath, "A Secret file is required")
}

static def readFile(def filePath, String errMessage) {
	try {
		def data = new Yaml().load(new FileInputStream(new File(filePath)))
		
		// Sanitize by removing the name and namespace
		data?.metadata?.remove('namespace')
		if(data?.metadata?.labels == null && data?.metadata != null) {
			// only set metadata on objects that have it
			data?.metadata?.labels = [:]
		}

		return data
	} catch(FileNotFoundException fnfe) {
		throw new RuntimeException(errMessage)
	}
}