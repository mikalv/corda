package net.corda.plugins

import com.typesafe.config.*
import groovy.lang.Closure
import net.corda.cordform.CordformNode
import net.corda.cordform.RpcSettings
import org.gradle.api.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Represents a node that will be installed.
 */
class Node(private val project: Project) : CordformNode() {
    companion object {
        @JvmStatic
        val webJarName = "corda-webserver.jar"
        private val configFileProperty = "configFile"
    }

    /**
     * Set the list of CorDapps to install to the plugins directory. Each cordapp is a fully qualified Maven
     * dependency name, eg: com.example:product-name:0.1
     *
     * @note Your app will be installed by default and does not need to be included here.
     * @note Type is any due to gradle's use of "GStrings" - each value will have "toString" called on it
     */
    var cordapps = mutableListOf<Any>()
    internal var additionalCordapps = mutableListOf<File>()
    internal lateinit var nodeDir: File
        private set
    internal lateinit var rootDir: File
        private set
    internal lateinit var containerName: String
        private set
    internal var rpcSettings: RpcSettings = RpcSettings()
        private set
    internal var webserverJar: String? = null
        private set

    /**
     * Sets whether this node will use HTTPS communication.
     *
     * @param isHttps True if this node uses HTTPS communication.
     */
    fun https(isHttps: Boolean) {
        config = config.withValue("useHTTPS", ConfigValueFactory.fromAnyRef(isHttps))
    }

    /**
     * Sets the H2 port for this node
     */
    fun h2Port(h2Port: Int) {
        config = config.withValue("h2port", ConfigValueFactory.fromAnyRef(h2Port))
    }

    fun useTestClock(useTestClock: Boolean) {
        config = config.withValue("useTestClock", ConfigValueFactory.fromAnyRef(useTestClock))
    }

    /**
     * Specifies RPC settings for the node.
     */
    fun rpcSettings(configureClosure: Closure<in RpcSettings>) {
        rpcSettings = project.configure(RpcSettings(), configureClosure) as RpcSettings
        config = rpcSettings.addTo("rpcSettings", config)
    }

    /**
     * Enables SSH access on given port
     *
     * @param sshdPort The port for SSH server to listen on
     */
    fun sshdPort(sshdPort: Int?) {
        config = config.withValue("sshd.port", ConfigValueFactory.fromAnyRef(sshdPort))
    }

    /**
     * The webserver JAR to be used by this node.
     *
     * If not provided, the default development webserver is used.
     *
     * @param webserverJar The file path of the webserver JAR to use.
     */
    fun webserverJar(webserverJar: String) {
        this.webserverJar = webserverJar
    }

    internal fun build() {
        if (config.hasPath("webAddress")) {
            installWebserverJar()
        }
        installAgentJar()
        installBuiltCordapp()
        installCordapps()
    }

    internal fun buildDocker() {
        project.copy {
            it.apply {
                from(Cordformation.getPluginFile(project, "net/corda/plugins/Dockerfile"))
                from(Cordformation.getPluginFile(project, "net/corda/plugins/run-corda.sh"))
                into("$nodeDir/")
            }
        }
        installAgentJar()
        installBuiltCordapp()
        installCordapps()
    }

    internal fun rootDir(rootDir: Path) {
        if (name == null) {
            project.logger.error("Node has a null name - cannot create node")
            throw IllegalStateException("Node has a null name - cannot create node")
        }
        // Parsing O= part directly because importing BouncyCastle provider in Cordformation causes problems
        // with loading our custom X509EdDSAEngine.
        val organizationName = name.trim().split(",").firstOrNull { it.startsWith("O=") }?.substringAfter("=")
        val dirName = organizationName ?: name
        containerName = dirName.replace("\\s+".toRegex(), "-").toLowerCase()
        this.rootDir = rootDir.toFile()
        nodeDir = File(this.rootDir, dirName.replace("\\s", ""))
        Files.createDirectories(nodeDir.toPath())
    }

    private fun configureProperties() {
        config = config.withValue("rpcUsers", ConfigValueFactory.fromIterable(rpcUsers))
        if (notary != null) {
            config = config.withValue("notary", ConfigValueFactory.fromMap(notary))
        }
        if (extraConfig != null) {
            val extraProperties = ConfigFactory.parseMap(extraConfig)
            config = if (config.hasPath("custom")) {
                val customConfig = config.getConfig("custom")
                config.withValue("custom", customConfig.withFallback(extraProperties).root())
            } else {
                config.withValue("custom", extraProperties.root())
            }
        }
    }

    /**
     * Installs the corda webserver JAR to the node directory
     */
    private fun installWebserverJar() {
        // If no webserver JAR is provided, the default development webserver is used.
        val webJar = if (webserverJar == null) {
            project.logger.info("Using default development webserver.")
            Cordformation.verifyAndGetRuntimeJar(project, "corda-webserver")
        } else {
            project.logger.info("Using custom webserver: $webserverJar.")
            File(webserverJar)
        }

        project.copy {
            it.apply {
                from(webJar)
                into(nodeDir)
                rename(webJar.name, webJarName)
            }
        }
    }

    /**
     * Installs this project's cordapp to this directory.
     */
    private fun installBuiltCordapp() {
        val cordappsDir = File(nodeDir, "cordapps")
        project.copy {
            it.apply {
                from(project.tasks.getByName("jar"))
                into(cordappsDir)
            }
        }
    }

    /**
     * Installs the jolokia monitoring agent JAR to the node/drivers directory
     */
    private fun installAgentJar() {
        // TODO: improve how we re-use existing declared external variables from root gradle.build
        val jolokiaVersion = try { project.rootProject.ext<String>("jolokia_version") } catch (e: Exception) { "1.3.7" }
        val agentJar = project.configuration("runtime").files {
            (it.group == "org.jolokia") &&
                    (it.name == "jolokia-jvm") &&
                    (it.version == jolokiaVersion)
            // TODO: revisit when classifier attribute is added. eg && (it.classifier = "agent")
        }.first()  // should always be the jolokia agent fat jar: eg. jolokia-jvm-1.3.7-agent.jar
        project.logger.info("Jolokia agent jar: $agentJar")
        if (agentJar.isFile) {
            val driversDir = File(nodeDir, "drivers")
            project.copy {
                it.apply {
                    from(agentJar)
                    into(driversDir)
                }
            }
        }
    }

    private fun createTempConfigFile(configObject: ConfigObject, fileNameTrail: String): File {
        val options = ConfigRenderOptions
                .defaults()
                .setOriginComments(false)
                .setComments(false)
                .setFormatted(true)
                .setJson(false)
        val configFileText = configObject.render(options).split("\n").toList()
        // Need to write a temporary file first to use the project.copy, which resolves directories correctly.
        val tmpDir = File(project.buildDir, "tmp")
        Files.createDirectories(tmpDir.toPath())
        val fileName = "${nodeDir.name}_$fileNameTrail"
        val tmpConfFile = File(tmpDir, fileName)
        Files.write(tmpConfFile.toPath(), configFileText, StandardCharsets.UTF_8)
        return tmpConfFile
    }

    /**
     * Installs the configuration file to the root directory and detokenises it.
     */
    internal fun installConfig() {
        configureProperties()
        val tmpConfFile = createTempConfigFile(config.toNodeOnly().root(), "node.conf")
        appendOptionalConfig(tmpConfFile)
        project.copy {
            it.apply {
                from(tmpConfFile)
                into(rootDir)
            }
        }
        if (config.hasPath("webAddress")) {
            val webServerConfigFile = createTempConfigFile(config.toWebServerOnly().root(), "web-server.conf")
            project.copy {
                it.apply {
                    from(webServerConfigFile)
                    into(rootDir)
                }
            }
        }
    }

    private fun Config.toNodeOnly(): Config {

        return if (hasPath("webAddress")) {
            withoutPath("webAddress").withoutPath("useHTTPS")
        } else {
            this
        }
    }

    private fun Config.toWebServerOnly(): Config {

        var config = ConfigFactory.empty()
        config = copyTo("webAddress", config)
        config = copyTo("myLegalName", config)
        if (hasPath("rpcOptions.address") || hasPath("rpcAddress")) {
            config += "rpcAddress" to if (hasPath("rpcOptions.address")) {
                getValue("rpcOptions.address")
            } else {
                getValue("rpcAddress")
            }
        }
        config = copyTo("rpcUsers", config)
        config = copyTo("useHTTPS", config)
        config = copyTo("baseDirectory", config)
        config = copyTo("keyStorePassword", config)
        config = copyTo("trustStorePassword", config)
        config = copyTo("exportJMXto", config)
        config = copyTo("custom", config)
        return config
    }

    private fun Config.copyTo(key: String, target: Config, targetKey: String = key): Config {

        return if (hasPath(key)) {
            target + (targetKey to getValue(key))
        } else {
            target
        }
    }

    private operator fun Config.plus(property: Pair<String, Any>) = withValue(property.first, ConfigValueFactory.fromAnyRef(property.second))

    /**
     * Installs the Dockerized configuration file to the root directory and detokenises it.
     */
    internal fun installDockerConfig() {
        configureProperties()
        val dockerConf = config
                .withValue("p2pAddress", ConfigValueFactory.fromAnyRef("$containerName:$p2pPort"))
                .withValue("rpcSettings.address", ConfigValueFactory.fromAnyRef("$containerName:${rpcSettings.port}"))
                .withValue("rpcSettings.adminAddress", ConfigValueFactory.fromAnyRef("$containerName:${rpcSettings.adminPort}"))
                .withValue("detectPublicIp", ConfigValueFactory.fromAnyRef(false))

        val tmpConfFile = createTempConfigFile(dockerConf.toNodeOnly().root(), "node.conf")
        appendOptionalConfig(tmpConfFile)
        project.copy {
            it.apply {
                from(tmpConfFile)
                into(rootDir)
            }
        }
        if (config.hasPath("webAddress")) {
            val webServerConfigFile = createTempConfigFile(dockerConf.toWebServerOnly().root(), "web-server.conf")
            project.copy {
                it.apply {
                    from(webServerConfigFile)
                    into(rootDir)
                }
            }
        }
    }

    /**
     * Appends installed config file with properties from an optional file.
     */
    private fun appendOptionalConfig(confFile: File) {
        val optionalConfig: File? = when {
            project.findProperty(configFileProperty) != null -> //provided by -PconfigFile command line property when running Gradle task
                File(project.findProperty(configFileProperty) as String)
            config.hasPath(configFileProperty) -> File(config.getString(configFileProperty))
            else -> null
        }

        if (optionalConfig != null) {
            if (!optionalConfig.exists()) {
                project.logger.error("$configFileProperty '$optionalConfig' not found")
            } else {
                confFile.appendBytes(optionalConfig.readBytes())
            }
        }
    }

    /**
     * Installs other cordapps to this node's cordapps directory.
     */
    internal fun installCordapps() {
        additionalCordapps.addAll(getCordappList())
        val cordappsDir = File(nodeDir, "cordapps")
        project.copy {
            it.apply {
                from(additionalCordapps)
                into(cordappsDir)
            }
        }
    }

    /**
     * Gets a list of cordapps based on what dependent cordapps were specified.
     *
     * @return List of this node's cordapps.
     */
    private fun getCordappList(): Collection<File> {
        // Cordapps can sometimes contain a GString instance which fails the equality test with the Java string
        @Suppress("RemoveRedundantCallsOfConversionMethods")
        val cordapps: List<String> = cordapps.map { it.toString() }
        return project.configuration("cordapp").files {
            cordapps.contains(it.group + ":" + it.name + ":" + it.version)
        }
    }
}
