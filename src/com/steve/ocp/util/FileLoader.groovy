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
	def data
	try {
		data = new Yaml().load(new FileInputStream(new File(filePath)))
	} catch(FileNotFoundException fnfe) {
		data = [
			"apiVersion": "v1",
			"kind": "ConfigMap",
			"metadata": [
				"creationTimestamp": null,
			]
		]
	}
	
	return sanitize(data)
}

static def readSecret(def filePath) {
	def data
	try {
		data = new Yaml().load(new FileInputStream(new File(filePath)))
	} catch(FileNotFoundException fnfe) {
		data = [
			"apiVersion": "v1",
			"kind": "Secret",
			"data": [
				"default": ""
			],
			"metadata": [
				"creationTimestamp": null,
			],
			"type": "Opaque"
		]
	}

	return sanitize(data)
}

static def sanitize(def data) {
	// Sanitize by removing the name and namespace
	data?.metadata?.remove('namespace')
	if(data?.metadata?.labels == null && data?.metadata != null) {
		// only set metadata on objects that have it
		data?.metadata?.labels = [:]
	}
	return data
}

static def readFile(def filePath, String errMessage) {
	try {
		def data = new Yaml().load(new FileInputStream(new File(filePath)))
		return sanitize(data)
	} catch(FileNotFoundException fnfe) {
		throw new RuntimeException(errMessage)
	}
}