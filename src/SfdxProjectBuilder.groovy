class SfdxProjectBuilder implements Serializable {

  private final def _ // the member variable that contains the jenkinsFileScript

  private def sfdxScratchOrgDefinitionFile = "config/project-scratch-def.json"

  private def dockerImage

  private def usingDockerPipelinePlugin = false

  private def usingKubernetesContainerPlugin = false

  private def dockerImageName = 'salesforce/salesforcedx:latest-full'

  private def workingArtifactDirectory 

  private def sfdxScratchOrgAlias

  private def sfdxNewPackage

  private def sfdxNewPackageVersion

  private def sfdxNewPackageVersionId

  private def installationKeys

  private def packageInstallationKey

  private def loggingDebugMessages = false

  private def alwaysBuildPackage = false

  private def doNotBuildPackage = false

  private def slackChannelName

  private def slackResponseThreadId

  private def sendThreadedSlackMessages = true

  private def notifyOnSuccessfulBuilds = false 

  private def slackNotificationsIsActive = false

  private def scratchOrgWasCreated = false

  private def scratchOrgShouldBeDeleted = true

  private def branchesToBuildPackageFromList = ['master', 'main']

  private def upstreamProjectsToTriggerFrom = []

  private def upstreamProjectsToTriggerFromPrefix

  private boolean dependencyBuildsBranchMasterMainAndNullAreTheSame = true

  private def numberOfBuildsToKeep = '30'

  private def stageToStopBuildAt = 99

  // the parsed contents of the SFDX project's configuration
  private def sfdxPackage

  SfdxProjectBuilder(def jenkinsFileScript) {
    _ = jenkinsFileScript
  }

  public void execute() {
    _.withFolderProperties {
      initializeBuildClass()

      if ( this.usingKubernetesContainerPlugin ) {
        _.node('salesforcedx') {
            processInnerNode()
        } // node
      } else {
        _.node {

          processInnerNode()

        } // node
      }
    }
  }

  public SfdxProjectBuilder setSlackChannelToNotify(def slackChannelName) {
    if ( ! slackChannelName.empty ) {
      this.slackChannelName = slackChannelName
      _.echo("SfdxProjectBuilder Parameter : Slack notifications will go to change ${slackChannelName}")
    }
    return this
  }

  public SfdxProjectBuilder setScratchOrgDefFile(def scratchOrgDefFile) {
    if ( ! scratchOrgDefFile.empty ) {
       _.echo("SfdxProjectBuilder Parameter : scratchOrgDefFile has been set to ${scratchOrgDefFile}")
       this.sfdxScratchOrgDefinitionFile = scratchOrgDefFile
    }
    return this
  }

  public SfdxProjectBuilder setDependencyInstallationKeys(def keysString) {
    if ( keysString != null ) {
      this.installationKeys = keysString
      _.echo('SfdxProjectBuilder Parameter : installationKeys has been set')
    }
    return this
  }

  public SfdxProjectBuilder setPackageInstallationKey( def packageInstallationKey ) {
    if ( packageInstallationKey != null && !packageInstallationKey.empty ) {
      this.packageInstallationKey = packageInstallationKey
      _.echo('SfdxProjectBuilder Parameter : packageInstallationKey has been set')
    }
    return this
  }

  public SfdxProjectBuilder alwaysBuildPackage() {
    this.alwaysBuildPackage = true
    _.echo('SfdxProjectBuilder Parameter set : Always building a package')
    if ( this.doNotBuildPackage ) {
      _.error('alwaysBuildPackage() and doNotBuildPackage() cannot both be specified')
    }
    return this
  }

  public SfdxProjectBuilder doNotBuildPackage() {
    this.doNotBuildPackage = true
    _.echo('SfdxProjectBuilder Parameter set : No package will be built.  Overrides all other considerations.')
    if ( this.alwaysBuildPackage ) {
      _.error('alwaysBuildPackage() and doNotBuildPackage() cannot both be specified')
    }
    return this
  }

  public SfdxProjectBuilder alwaysNotifyOnSuccess() {
    this.notifyOnSuccessfulBuilds = true
    _.echo('SfdxProjectBuilder Parameter set : Notify on successful builds')
    return this
  }

  public SfdxProjectBuilder setSlackNotificationsOff() {
    this.slackNotificationsIsActive = false
    _.echo('SfdxProjectBuilder Parameter set : Slack Notifications turned off')
    return this
  }

  public SfdxProjectBuilder setSlackNotificationsOn() {
    this.slackNotificationsIsActive = true
    _.echo('SfdxProjectBuilder Parameter set : Slack Notifications turned on')
    return this
  }

  public SfdxProjectBuilder preserveScratchOrg() {
    this.scratchOrgShouldBeDeleted = false
    _.echo('SfdxProjectBuilder Parameter set : Scratch Org will be preserved')
    return this
  }

  public SfdxProjectBuilder setUpstreamProjectToTriggerBuildFrom( String jenkinsBuildJobName ) {
    if ( jenkinsBuildJobName != null && !jenkinsBuildJobName.empty ) {
      this.upstreamProjectsToTriggerFrom.add( jenkinsBuildJobName )
      _.echo("SfdxProjectBuilder Parameter set : Added ${jenkinsBuildJobName} to the upstream project build triggers")
    }
    return this
  }

  public SfdxProjectBuilder setDockerImageName( String dockerImageName ) {
    if ( dockerImageName != null && !dockerImageName.empty ) {
      this.dockerImageName = dockerImageName
      _.echo("SfdxProjectBuilder Parameter set : Setting docker image to be ${dockerImageName}")
    }
    return this
  }

  public SfdxProjectBuilder setNumberOfBuildsToKeep( Integer numberOfBuildsToKeep ) {
    if ( numberOfBuildsToKeep != null ) {
      this.numberOfBuildsToKeep = numberOfBuildsToKeep.toString()
      _.echo("SfdxProjectBuilder Parameter set : Setting number of builds to keep to be ${numberOfBuildsToKeep.toString()}")
    }
    return this
  }

  public SfdxProjectBuilder stopBuildAtStage( Integer stageToStopBuildAt ) {
    if ( stageToStopBuildAt != null ) {
      this.stageToStopBuildAt = stageToStopBuildAt
      _.echo("SfdxProjectBuilder Parameter set : Stopping build after stage ${stageToStopBuildAt.toString()}")
    }
    return this
  }

  public SfdxProjectBuilder setDebugOn() {
    this.loggingDebugMessages = true
    _.echo('SfdxProjectBuilder Parameter set : Logging of debug messages is turned on')
    return this
  }

  // vo id setBuildDescription(Map args) {
  //   jenkinsFileScript.currentBuild.displayName = args.title
  //   jenkinsFileScript.currentBuild.description = args.description
  // }

  private void processInnerNode() {
      sendSlackMessage(
        color: 'good',
        message: "Build ${_.env.JOB_NAME} ${_.env.BUILD_NUMBER} (<${_.env.BUILD_URL}|Open>)",
        isHeaderMessage: true
      )

      // checkout the main source code for the project.
      _.checkout _.scm

      // start the pipeline
      _.pipeline {

        _.properties([
          // ensure that concurrent builds on the same project is not possible
          _.disableConcurrentBuilds(),

          _.buildDiscarder(_.logRotator(numToKeepStr: this.numberOfBuildsToKeep)),

          _.pipelineTriggers(
            processProjectTriggers()
          )
          
        ])

        if ( usingDockerPipelinePlugin ) {
          _.echo('About to setup dockerImage')
          // ensure that we have the latest
          this.dockerImage.pull()
          this.dockerImage.inside('-e HOME=/tmp -e NPM_CONFIG_PREFIX=/tmp/.npm') {
            processStages() 
          }
        }
        else if ( usingKubernetesContainerPlugin ) {
          // Setup Kubernetes POD here
          _.container('salesforcedx') {  // salesforcedx
            processStages()
          }
        }
        else {
          _.echo("No docker image specified")
          processStages()
        }
        
      } // pipeline
  }

  private void processStages() {
    try {
      if ( this.stageToStopBuildAt >= 1 ) {
        _.stage('Validate') {
          sendSlackMessage(
            color: 'good',
            message: "Validation stage"
          )
          validateStage()
        } // stage: Validate
      }

      if ( this.stageToStopBuildAt >= 2 ) {
        _.stage('Initialize') {
          sendSlackMessage(
            color: 'good',
            message: "Initialization stage"
          )
          initializeStage()
        } // stage: Initialize
      }

      if ( this.stageToStopBuildAt >= 3 ) {
        _.stage('Process Resources') {
          sendSlackMessage(
            color: 'good',
            message: "Processing Resources stage"
          )
          processResourcesStage()
        } // stage: Process Resources
      }

      if ( this.stageToStopBuildAt >= 4 ) {
        _.stage('Compile') {
          sendSlackMessage(
            color: 'good',
            message: "Compilation stage"
          )
          compileStage()
        } // stage: Compile
      }

      if ( this.stageToStopBuildAt >= 5 ) {
        _.stage('Test') {
          sendSlackMessage(
            color: 'good',
            message: "Testing stage"
          )
          testStage()
        } // stage: Test
      }

      if ( this.stageToStopBuildAt >= 6 ) {
        _.stage('Package') {
          sendSlackMessage(
            color: 'good',
            message: "Packaging stage"
          )
          packageStage()
        } // stage: Package
      }

      if ( this.stageToStopBuildAt >= 7 ) {
        _.stage('Artifact Recording') {
          sendSlackMessage(
            color: 'good',
            message: "Artifact Recording stage"
          )
          artifactRecordingStage()
        } // stage: Artifact Recording
      } 

      postSuccess()
    }
    catch (ex) {
      postFailure(ex)
    }
    finally {
      postAlways()
    }
  }

  void initializeStage() {
    
    installRequiredCLIPlugins()
    // setup this build's unique artifact directory
    _.sh "mkdir -p ${this.workingArtifactDirectory}"

    readAndParseSFDXProjectFile()
    authenticateToDevHub()
    createScratchOrg()

  }

  void validateStage() {
    isProjectFileExisting()
    isEnvVarPopulatedConnectedAppConsumerKeyDH()
    isEnvVarPopulatedSFDXDevHubUsername()
    isEnvVarPopulatedSFDXDevHubHost()
    isEnvVarPopulatedJWTCredIdDH()

    // def rmsg = _.sh returnStdout: true, script: "pwd"
    // _.echo(rmsg)
    // // rmsg = _.sh returnStdout: true, script: "ls -lap /.local/"
    // // _.echo(rmsg)
    // rmsg = _.sh returnStdout: true, script: 'ls -lap $HOME'
    // _.echo(rmsg)
    // rmsg = _.sh returnStdout: true, script: 'ls -lap /usr/local/lib/sfdx'
    // _.echo(rmsg)
    // rmsg = _.sh returnStdout: true, script: 'ls -lap /usr/local/lib/sfdx/node_modules'
    // _.echo(rmsg)
    // rmsg = _.sh returnStdout: true, script: 'ls -lap /usr/local/lib/sfdx/node_modules/@salesforce'
    // _.echo(rmsg)
    
    
    // rmsg = _.sh returnStdout: true, script: 'chown -R root /usr/local/lib/sfdx'
    // _.echo(rmsg)

    // rmsg = _.sh returnStdout: true, script: 'ls -lap /usr/local/lib/sfdx'
    // _.echo(rmsg)

    // // _.sh returnStdout: true, script: "ls -lap /root/.local/share/sfdx/node_modules/"
    // def rmsgForPluginCheck = _.sh returnStdout: true, script: "sfdx plugins"
    // _.echo(rmsgForPluginCheck)
    // def rmsgInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install @dx-cli-toolbox/sfdx-toolbox-package-utils"
    // _.echo(rmsgInstall)
    // rmsg = _.sh returnStdout: true, script: "sfdx plugins"
    // _.echo(rmsg)
    // rmsg = _.sh returnStdout: true, script: 'ls -lap $HOME'
    // _.echo(rmsg)
  }

  void processResourcesStage() {
    installAllDependencies()
  }

  void compileStage() {
    compileCode()
  }

  void testStage() {
    // Give the code time to settle down before the unit tests begin
    _.sleep time: 2, unit: 'MINUTES'
    // need to a the parallel tage here along with PMD task

    // _.failFast true // this is part of the declarative syntax.  Is there an equivalent in the scripted model?
    
    // _.parallel { // not sure why this is not working.  Need to find equivalent in teh scripted model.
      executeUnitTests()
    // } // parallel
  }

  void packageStage() {
    packageTheProject()
  }

  void artifactRecordingStage() {
    // Disabling this task until Salesforce's release of the #Spring21.  The reason is that there are serious
    //    issues with the Salesforce CLI "force:package:installed:list" command timing out.  
    //    As of 11/30/2020, Salesforce says that there is a chance that the fix will arrive early.  When it does
    //    this feature will be reactivated.
    // archiveAllBuildArtifacts()
  }

  void postSuccess() {
    if ( this.notifyOnSuccessfulBuilds || ( _.currentBuild.previousBuild != null && _.currentBuild.resultIsBetterOrEqualTo( _.currentBuild.previousBuild.currentResult ) ) ) {
      sendSlackMessage(
        color: 'good',
        message: "Build completed ${_.env.JOB_NAME} ${_.env.BUILD_NUMBER} (<${_.env.BUILD_URL}|Open>)",
        isFooterMessage: true
      )
    }
  }

  void postFailure(def ex) {
    _.echo(ex.getMessage())
  
    sendSlackMessage(
      color: 'danger',
      message: "Build failed ${_.env.JOB_NAME} ${_.env.BUILD_NUMBER} (<${_.env.BUILD_URL}|Open>)",
      isFooterMessage: true,
      isBroadcastingReply: true
    )

  }

  void postAlways() {
    deleteScratchOrg()

    // temporary workaround pending resolution to this issue https://github.com/forcedotcom/cli/issues/81.  Also, see reference in authenticateToDevHub() method
    _.fileOperations([_.fileDeleteOperation(excludes: '', includes: 'server.key')])
  }

  private void sendSlackMessage(Map args) {
    
    debug("sendSlackMessage with message of '${args.message}'")
    debug("sendThreadedSlackMessages == ${sendThreadedSlackMessages}")

    if ( this.slackNotificationsIsActive ) {

      // header messages -- should be shown if system allows it 
      // thread messages -- should be shown only if slackResponseThreadId != null
      // footer messages -- should always be shown -- if slackResponseThreadId != null, then footer message should be part of thread  else should be in slackChannelName

      def shouldSendMessage = false 
      def slackChannelToSendMessageTo
      def shouldReplyBroadcast = 'false'

      if ( args.isHeaderMessage && this.sendThreadedSlackMessages) {
      
        if ( this.slackChannelName ) {
          slackChannelToSendMessageTo = this.slackChannelName
          // this messages is the start of a Slack thread in the Slack channel specified
          // slackResponse = _.slackSend channel: "${this.slackChannelName}", color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: false 
      
        // } else {
          // this messages is the start of a Slack thread in the default Slack channel specified in the Global Config of Jenkins
          // slackResponse = _.slackSend color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: false
        }
        shouldSendMessage = true
      } else if ( args.isFooterMessage ) {
        if ( this.slackResponseThreadId ) {
          slackChannelToSendMessageTo = this.slackResponseThreadId
        } else if ( this.slackChannelName ) {
          slackChannelToSendMessageTo = this.slackChannelName
        }
        if ( args.isBroadcastingReply ) {
          shouldReplyBroadcast = 'true'
        }
        shouldSendMessage = true
      } else if ( this.sendThreadedSlackMessages && this.slackResponseThreadId ) {
        shouldSendMessage = true
        slackChannelToSendMessageTo = this.slackResponseThreadId
      }

      debug("shouldSendMessage == ${shouldSendMessage}")

      if ( shouldSendMessage ) {

        debug("slackChannelToSendMessageTo == ${slackChannelToSendMessageTo}")
        debug("shouldReplyBroadcast == ${shouldReplyBroadcast}")
  
        def slackResponse

        if ( slackChannelToSendMessageTo ) {
          slackResponse = _.slackSend channel: "${slackChannelToSendMessageTo}", color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: false, replyBroadcast: "${shouldReplyBroadcast}"
        } else {
          slackResponse = _.slackSend color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: false, replyBroadcast: "${shouldReplyBroadcast}"
        }
        debug("slackResponse == ${slackResponse}")
        debug("slackResponse.threadId == ${slackResponse.threadId}")
        debug("slackResponseThreadId == ${slackResponseThreadId}")
        if ( this.slackResponseThreadId == null && slackResponse && slackResponse.threadId ) {
          // set the Slack Thread Id for future updates
          this.slackResponseThreadId = slackResponse.threadId
        }
      }

      // } else if ( this.slackResponseThreadId ) {
      //   // this message should be appended to an existing Slack thread
      //   slackResponse = _.slackSend channel: "${this.slackResponseThreadId}", color: "${args.color}", failOnError: true, message: "${args.message}", notifyCommitters: false
      // }       
    } else {
      _.echo("Slack notifications are currently off")
    }
    // def rmsg =  _.sh returnStdout: true, script: "sfdx force:auth:jwt:grant --clientid ${_.env.CONNECTED_APP_CONSUMER_KEY_DH} --username ${_.env.SFDX_DEV_HUB_USERNAME} --jwtkeyfile server.key --instanceurl ${_.env.SFDX_DEV_HUB_HOST} --json"
    // Potential enhancement -- multi-threaded slack messages
    // def slackResponse = slackSend(channel: "cool-threads", message: "Here is the primary message")
    // slackSend(channel: slackResponse.threadId, message: "Thread reply #1")
    // slackSend(channel: slackResponse.threadId, message: "Thread reply #2")
  }

  private void isProjectFileExisting()
  {
    def sfdxProjectFileExists = _.fileExists 'sfdx-project.json'
    if ( ! sfdxProjectFileExists ) {
        _.error 'SFDX project file (sfdx-project.json) not found.'
    }
  }

  private void isEnvVarPopulatedConnectedAppConsumerKeyDH()
  {
    isEnvVarPopulated(_.env.CONNECTED_APP_CONSUMER_KEY_DH, 'CONNECTED_APP_CONSUMER_KEY_DH')
  }

  private void isEnvVarPopulatedSFDXDevHubUsername()
  {
    isEnvVarPopulated(_.env.SFDX_DEV_HUB_USERNAME, 'SFDX_DEV_HUB_USERNAME')
  }

  private void isEnvVarPopulatedSFDXDevHubHost()
  {
    isEnvVarPopulated(_.env.SFDX_DEV_HUB_HOST, 'SFDX_DEV_HUB_HOST')
  }

  private void isEnvVarPopulatedJWTCredIdDH()
  {
    isEnvVarPopulated(_.env.JWT_CRED_ID_DH, 'JWT_CRED_ID_DH')
  }

  private void isEnvVarPopulated(enironmentVariable, enironmentVariableName)
  {
    // _.echo( enironmentVariable )
    // _.echo( enironmentVariableName )
    if ( ! enironmentVariable ) {
      _.error "Environment Variable ${enironmentVariableName} is null"
    }
  }

  private void initializeBuildClass() {
    initializeBuildScriptVariables()
    initializeDockerImage()
  }

  private void initializeDockerImage() {
    debug("usingDockerPipelinePlugin == ${usingDockerPipelinePlugin}")
    debug("dockerImageName == ${dockerImageName}")
    if ( this.usingDockerPipelinePlugin ) {
      this.dockerImage = _.docker.image(this.dockerImageName)
      debug("Using dockerImage ${this.dockerImageName} with Docker Pipeline Plugin")
    }
    else if ( this.usingKubernetesContainerPlugin ) {
      // WATCH - Kubernetes sets the docker image as part of the podTemplate
      // this.dockerImage = _.docker.image(this.dockerImageName)
      debug("Using dockerImage ${this.dockerImageName} with Kubernetes Container Plugin")
    }
  }

  private void initializeBuildScriptVariables() {
    this.workingArtifactDirectory = "target/${_.env.BUILD_NUMBER}"
    this.sfdxScratchOrgAlias = "bluesphere-${_.env.BUILD_TAG.replaceAll("/", "_").replaceAll(" ","_")}"
    if ( _.env.TREAT_DEPENDENCY_BUILDS_BRANCH_MASTER_MAIN_AND_NULL_THE_SAME != null ) {
      this.dependencyBuildsBranchMasterMainAndNullAreTheSame = _.env.TREAT_DEPENDENCY_BUILDS_BRANCH_MASTER_MAIN_AND_NULL_THE_SAME.toBoolean()
    }
    // TODO: Figure out way to use env vars to drive the container configuration

    if ( _.env.JENKINS_SFDX_CORE_CI_LIB_CONTAINER_OPTION ) {
      this.usingDockerPipelinePlugin = false
      this.usingKubernetesContainerPlugin = false
      if ( _.env.JENKINS_SFDX_CORE_CI_LIB_CONTAINER_OPTION == 'docker-workflow' ) {
        this.usingDockerPipelinePlugin = true 
      } else if ( _.env.JENKINS_SFDX_CORE_CI_LIB_CONTAINER_OPTION == 'kubernetes' ) {
        this.usingKubernetesContainerPlugin = true
      } else {
        _.error( "Environment variable JENKINS_SFDX_CORE_CI_LIB_CONTAINER_OPTION set to ${_.env.JENKINS_SFDX_CORE_CI_LIB_CONTAINER_OPTION} but not a valid option" )
      }
    }

    if ( _.env.DEFAULT_DOCKER_IMAGE_NAME ) {
      this.dockerImageName = _.env.DEFAULT_DOCKER_IMAGE_NAME
    }

    if ( _.env.UPSTREAM_PROJECT_PREFIX ) {
      this.upstreamProjectsToTriggerFromPrefix = _.env.UPSTREAM_PROJECT_PREFIX
    }
  }

  private void readAndParseSFDXProjectFile() {
    _.echo('Deserialize the sfdx-project.json ')
    this.sfdxPackage = jsonParse( _.readFile('sfdx-project.json') )
  }

  private void authenticateToDevHub() {
    _.echo('Authenticate to the Dev Hub ')
    // _.echo(_.env.JWT_CRED_ID_DH)
    _.withCredentials( [ _.file( credentialsId: _.env.JWT_CRED_ID_DH, variable: 'jwt_key_file') ] ) {
        // temporary workaround pending resolution to this issue https://github.com/forcedotcom/cli/issues/81
        _.sh returnStatus: true, script: "cp ${_.jwt_key_file} ./server.key"
        // _.fileOperations([_.fileCopyOperation(excludes: '', flattenFiles: false, includes: _.jwt_key_file, targetLocation: './server.key')])  // some issue with the masking of the file name.  Need to sort it out

        _.echo("Authenticating To Dev Hub...")
        

        // def rc = _.sh returnStatus: true, script: "sfdx force:auth:jwt:grant --clientid ${_.env.CONNECTED_APP_CONSUMER_KEY_DH} --username ${_.env.SFDX_DEV_HUB_USERNAME} --jwtkeyfile server.key --instanceurl ${_.env.SFDX_DEV_HUB_HOST}"
        // if (rc != 0) { 
        //   _.error "hub org authorization failed" 
        // }

      try {
        def rmsg =  _.sh returnStdout: true, script: "sfdx force:auth:jwt:grant --clientid ${_.env.CONNECTED_APP_CONSUMER_KEY_DH} --username ${_.env.SFDX_DEV_HUB_USERNAME} --jwtkeyfile server.key --instanceurl ${_.env.SFDX_DEV_HUB_HOST} --json"
        // _.echo('mark C')
        def response = jsonParse( rmsg )
        // _.echo('mark D')
        // _.echo(response)
        // _.echo('mark E')
      }
      catch (ex) {
        _.echo('------------------------------------------------------')
        // _.echo('mark F')
        _.echo(ex.getMessage())
        // _.echo('mark G')
        _.echo('------------------------------------------------------')
        _.error "hub org authorization failed" 
      }

    }
  }

  private void createScratchOrg() {
    _.echo('Creating scratch org')

    def commandScriptString = "sfdx force:org:create --definitionfile ${this.sfdxScratchOrgDefinitionFile} --json --durationdays 1 --setalias ${this.sfdxScratchOrgAlias} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME} --wait 30"

    def response

    try {
      def rmsg = _.sh returnStdout: true, script: commandScriptString

      response = jsonParse( rmsg )
    }
    catch (ex) {
      _.echo('------------------------------------------------------')
      _.echo(ex.getMessage())
      _.echo('------------------------------------------------------')
      if (ex.getMessage().contains('OPERATION_TOO_LARGE')) {
        _.echo('exception message contains OPERATION_TOO_LARGE')
        _.echo('------------------------------------------------------')
      }
      _.echo(ex.printStackTrace())
      _.error('scratch org creation failed')
    }

    if (response.status != 0 ) {
      if (response.name.equals('genericTimeoutMessage')) {
        // try one more time to create the scratch org
        _.echo('Original attempt to create scratch org timed out.  Trying to create one again.')
        rmsg = _.sh returnStdout: true, script: commandScriptString
        response = jsonParse( rmsg )
        if ( response.status != 0 ) {
          _.error "Failed to create Scratch Org -- ${response.message}"
        }
      }
      else {
        _.error "Failed to create Scratch Org -- ${response.message}"
      }
    }
    this.scratchOrgWasCreated = true
  }

  private void deleteScratchOrg() {
    if (this.scratchOrgWasCreated && this.scratchOrgShouldBeDeleted) {
      _.echo('Deleting scratch org')
      def rc = _.sh returnStatus: true, script: "sfdx force:org:delete --noprompt --targetusername ${this.sfdxScratchOrgAlias} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
      if (rc != 0) { 
        _.error "deletion of scratch org ${this.sfdxScratchOrgAlias} failed"
      }
    }
  }

  private void installAllDependencies() {
    // _.echo("env.BRANCH_NAME == ${_.env.BRANCH_NAME}")
    // _.echo("this.dependencyBuildsBranchMasterMainAndNullAreTheSame == ${this.dependencyBuildsBranchMasterMainAndNullAreTheSame}")
    // if ( _.env.BRANCH_NAME == 'master' ) {
    //   _.echo('branch_name == master')
    // }
    // if ( _.env.BRANCH_NAME != 'master' ) {
    //   _.echo('branch_name != master')
    // }

    // if ( !this.dependencyBuildsBranchMasterMainAndNullAreTheSame ) {
    //   _.echo('!this.dependencyBuildsBranchMasterMainAndNullAreTheSame == true')
    // } else {
    //   _.echo('!this.dependencyBuildsBranchMasterMainAndNullAreTheSame == false')
    // }

    // if ( _.env.BRANCH_NAME == 'master' && ( !this.dependencyBuildsBranchMasterMainAndNullAreTheSame ) ) {
    //   _.echo('secondary condition true')
    // } else {
    //   _.echo('secondary condition false')
    // }

    // if ( _.env.BRANCH_NAME != 'master' || ( _.env.BRANCH_NAME == 'master' && !this.dependencyBuildsBranchMasterMainAndNullAreTheSame ) ) {
    //   _.echo('complete condition true')
    // } else {
    //   _.echo('complete condition false')
    // }

    def commandScriptString = "sfdx toolbox:package:dependencies:install --wait 240 --noprecheck --targetusername ${this.sfdxScratchOrgAlias} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME} --json"
    
    if ( (_.env.BRANCH_NAME != 'master' && _.env.BRANCH_NAME != 'main') || ( (_.env.BRANCH_NAME == 'master' || _.env.BRANCH_NAME == 'main') && !this.dependencyBuildsBranchMasterMainAndNullAreTheSame ) ) {
      commandScriptString = commandScriptString + " --branch ${_.env.BRANCH_NAME}"
    }

    if ( this.installationKeys != null ) {
      // 1:MyPackage1Key 2: 3:MyPackage3Key
      commandScriptString = commandScriptString + " --installationkeys '" + this.installationKeys + "'"
    }

    // _.echo ("commandScriptString == ${commandScriptString}")
    
    def rmsg = _.sh returnStdout: true, script: commandScriptString

    // if ( rmsg.isEmpty() ) {
    //   // then this means that the toolbox plugin has not been installed on this server.
    //   installRequiredCLIPlugins()
    //   _.echo ("retrying the toolbox:package:dependencies:install command")
    //   rmsg = _.sh returnStdout: true, script: commandScriptString
    // }
  
    def response = jsonParse( rmsg )

    if (response.status != 0) {
      _.echo rmsg
      _.error "package dependency installed failed -- ${response.message}"
    }
    
  }

  private void installRequiredCLIPlugins() {
      _.echo ("installing the toolbox plugins")
      def rmsgToolboxInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install @dx-cli-toolbox/sfdx-toolbox-package-utils"
      _.echo rmsgToolboxInstall

      // _.echo ("installing the sfdmu plugins")
      // def rmsgSFDMUInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install sfdmu"
      // _.echo rmsgSFDMUInstall

      // _.echo ("installing the shane-sfdx-plugins  plugins")
      // def rmsgShaneSFDXPluginInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install shane-sfdx-plugins "
      // _.echo rmsgShaneSFDXPluginInstall

      // _.echo ("installing the sfpowerkit plugins")
      // def rmsgSFPowerKitInstall = _.sh returnStdout: true, script: "echo y | sfdx plugins:install sfpowerkit"
      // _.echo rmsgSFPowerKitInstall
  }

  private void compileCode() {
    _.echo("Push To Scratch Org And Compile")
    def rmsg = _.sh returnStdout: true, script: "sfdx force:source:push --json --targetusername ${this.sfdxScratchOrgAlias}"
    // printf rmsg

    def response = jsonParse( rmsg )

    if (response.status != 0) {
        _.error "push failed -- ${response.message}"
    }
  }

  private void executeUnitTests() {
    _.echo( 'Run All Local Apex Tests' )
    _.timeout(time: 120, unit: 'MINUTES') {
      // script {
      def rmsg 
      
      try {
        rmsg = _.sh returnStdout: true, label: 'Executing force:apex:test:run...', script: "sfdx force:apex:test:run --testlevel RunLocalTests --outputdir ${this.workingArtifactDirectory} --resultformat tap --codecoverage --wait 60 --json --targetusername ${this.sfdxScratchOrgAlias}"
      }
      catch (ex) {
        _.echo(ex.getMessage())
        _.echo('Restarting unit test run')
        // remove the test files from the previous run
        _.fileOperations([_.folderDeleteOperation( this.workingArtifactDirectory ), _.folderCreateOperation( this.workingArtifactDirectory )])

        // execute all unit tests a second time.  There is a bug with snapshots and CMDT-based 
        //      Dependency injection and Apex Unit Tests.  The workaround is to simply
        //      re-run the unit tests again.
        rmsg = _.sh returnStdout: true, label: 'Executing force:apex:test:run...', script: "sfdx force:apex:test:run --testlevel RunLocalTests --outputdir ${this.workingArtifactDirectory} --resultformat junit --codecoverage --wait 60 --json --targetusername ${this.sfdxScratchOrgAlias}"
      }
      finally {
        collectTestResults()
      }
      
      // Process all unit test reports
      def response = jsonParse( rmsg )
      if (response.status != 0) {
        _.echo(response)
        _.error "apex test run failed -- ${response.message}"
      }
      // } // script tag
    }
  }

  private void collectTestResults() {
    _.echo( "Collect All Test Results")
    _.junit keepLongStdio: true, testResults: "${this.workingArtifactDirectory}/**/*-junit.xml"
  }

  private void packageTheProject() {
    if ( ( ! alwaysBuildPackage 
          && ! branchesToBuildPackageFromList.contains(_.env.BRANCH_NAME) )
          || doNotBuildPackage
          ) {
      return
    }
    _.echo('Starting packaging process')

    def pathToUseForPackageVersionCreation

    // What is the default package and what is its directory?
    for ( packageDirectory in this.sfdxPackage.packageDirectories ) {
      _.echo("packageDirectory == ${packageDirectory}")
      if ( packageDirectory.default ) {
          _.echo("packageDirectory is default")
          pathToUseForPackageVersionCreation = packageDirectory.path 

          if (packageDirectory.package == null) {
            // there is no package specified in the this.sfdxPackage.  Simple exit out of this method
            _.echo('No package information configured on this project.')
            return
          }

          this.sfdxNewPackage = this.resolveAliasToId( packageDirectory.package, this.sfdxPackage )
          break 
      }
    }

    if ( this.sfdxNewPackage == null ) {
      _.error  "unable to determine this.sfdxNewPackage in stage:package"
    }

    if ( pathToUseForPackageVersionCreation == null ) {
      _.error  "unable to determine pathToUseForPackageVersionCreation in stage:package"
    }

    def commandScriptString = "sfdx force:package:version:create --path ${pathToUseForPackageVersionCreation} --json --codecoverage --tag ${_.env.BUILD_TAG.replaceAll(' ','-')} --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"

    // use the branch command flag only when the branch is not "master" or when it is "master" and the environment is not set to operate as "master == null"
    if ( (_.env.BRANCH_NAME != 'master' && _.env.BRANCH_NAME != 'main') || ( (_.env.BRANCH_NAME == 'master' || _.env.BRANCH_NAME == 'main') && !this.dependencyBuildsBranchMasterMainAndNullAreTheSame ) ) {
      commandScriptString = commandScriptString + " --branch ${_.env.BRANCH_NAME}"
    }

    if ( this.packageInstallationKey == null ) {
      commandScriptString = commandScriptString + ' --installationkeybypass'
    } else {
      commandScriptString = commandScriptString + " --installationkey '${this.packageInstallationKey}'"
    }

    _.echo ("commandScriptString == ${commandScriptString}")

    def rmsg = _.sh returnStdout: true, script: commandScriptString
    // printf rmsg

    def packageVersionCreationResponse = jsonParse(rmsg)

    // _.echo ("packageVersionCreationResponse == ${packageVersionCreationResponse}")

    if ( packageVersionCreationResponse.status != 0 ) {
        _.echo( packageVersionCreationResponse )
        _.error "package version creation has failed -- ${packageVersionCreationResponse.result.Error}"
    } else {

        this.sfdxNewPackageVersion = packageVersionCreationResponse.result

        if( this.sfdxNewPackageVersion.Status != 'Success') {
            // The package version creation is still underway
            def packageVersionCreationCheckResponseResult = ''

            _.timeout(360) {
                _.waitUntil {
                    // script {
                        // use the this.sfdxNewPackageVersion.Id for this command verses this.sfdxNewPackageVersionId because we are yet
                        //  certain that the package was created correctly
                        rmsg = _.sh returnStdout: true, script: "sfdx force:package:version:create:report --packagecreaterequestid ${this.sfdxNewPackageVersion.Id} --json --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
                        // printf rmsg

                        def packageVersionCreationCheckResponse = jsonParse(rmsg) 

                        if ( packageVersionCreationCheckResponse.status != 0 ) {
                          _.error "force:package:version:create:report failed -- ${packageVersionCreationCheckResponse.message}"
                        }

                        // _.echo ("packageVersionCreationCheckResponse == ${packageVersionCreationCheckResponse}")

                        // The JSON "result" is currently an array.  That is a SFDX bug -- W-4621618
                        // Refer to Salesforce DX Success Community post for details https://success.salesforce.com/0D53A00003OTsAD
                        this.sfdxNewPackageVersion = packageVersionCreationCheckResponse.result[0]
                        
                        if ( packageVersionCreationCheckResponse.status != 0 || this.sfdxNewPackageVersion.Status == 'Error' ) {
                          _.echo ("packageVersionCreationCheckResponse == ${packageVersionCreationCheckResponse}")
                          _.error "force:package:version:create:report failed -- ${this.sfdxNewPackageVersion.Error}"
                        }

                        def isPackageVersionCreationCompleted

                        // _.echo ( "this.sfdxNewPackageVersion.Status == ${this.sfdxNewPackageVersion.Status}" )
                        
                        if ( packageVersionCreationCheckResponse.status == 0 
                            && this.sfdxNewPackageVersion.Status == "Success") {
                            isPackageVersionCreationCompleted = true 
                        } else {
                            isPackageVersionCreationCompleted = false 
                        }
                        _.echo( "Current status == ${this.sfdxNewPackageVersion.Status}")

                        return isPackageVersionCreationCompleted
                    // } // script
                }
                _.echo("Exited the waitUntil phase")
            }
            _.echo("Exited the timeout phase")
        }
    }
    _.echo( "Exited the creation/check phase")
    // failure point is probably in this area
    // _.echo( "packageVersionCreationResponse == ${packageVersionCreationResponse}")

    this.sfdxNewPackageVersionId = this.sfdxNewPackageVersion.SubscriberPackageVersionId

    // tagging the build
    tagTheBuild()

    // _.echo( "this.sfdxNewPackageVersion == ${this.sfdxNewPackageVersion}")

    _.echo( "this.sfdxNewPackageVersionId == ${this.sfdxNewPackageVersionId}")
  }

  private void tagTheBuild() {
    _.echo("Tagging the build as '${_.env.BUILD_TAG}'")

//  for this to work, the GIT identiy must be established.  The following commands need to be run
      // git config user.email "you@example.com"
      // git config user.name "Your Name"
//     _.sh returnStdout: true, script: "git tag -m '${_.env.BUILD_TAG}' ${_.env.BUILD_TAG} "

    // _.sh returnStdout: true, script: "git push --tags"
    _.echo('Tagging successful')
  }

  private void archiveAllBuildArtifacts() {
    _.echo("finding all package versions dependencies and recording them for the build")

    // Get the list of package versions that are currently installed in the default scratch org
    def rmsg
    def jsonParsedResponse
    try {
      rmsg = _.sh returnStdout: true, script: "sfdx force:package:installed:list --json --targetusername ${this.sfdxScratchOrgAlias}"
      _.echo( rmsg )
      if ( rmsg != null ) {
        jsonParsedResponse = jsonParse(rmsg)
        _.echo("jsonParsedResponse.exitCode == " + jsonParsedResponse.exitCode)
        _.echo("jsonParsedResponse.name == " + jsonParsedResponse.name)
      }
      if ( rmsg == null || (jsonParsedResponse != null && jsonParsedResponse.exitCode == 1 && jsonParsedResponse.name.equals("QUERY_TIMEOUT") ) ) {
        _.echo("Sleeping for 2 minutes and will try again")
        _.sleep time: 1, unit: 'MINUTES'
        rmsg = _.sh returnStdout: true, script: "sfdx force:package:installed:list --json --targetusername ${this.sfdxScratchOrgAlias}"
        jsonParsedResponse = jsonParse(rmsg)
      }
    }
    catch (ex) {
      _.echo( ex.getMessage() )
      _.echo( rmsg )
      if ( rmsg != null ) {
        jsonParsedResponse = jsonParse(rmsg)
        _.echo("jsonParsedResponse.exitCode == " + jsonParsedResponse.exitCode)
        _.echo("jsonParsedResponse.name == " + jsonParsedResponse.name)
      }
      if ( rmsg == null || (jsonParsedResponse != null && jsonParsedResponse.exitCode == 1 && jsonParsedResponse.name.equals("QUERY_TIMEOUT") ) ) {
        _.echo("Sleeping for 2 minutes and will try again")
        _.sleep time: 1, unit: 'MINUTES'
        rmsg = _.sh returnStdout: true, script: "sfdx force:package:installed:list --json --targetusername ${this.sfdxScratchOrgAlias}"
        jsonParsedResponse = jsonParse(rmsg)
      }

    }

    def allPackageVersionsInstalledInScratchOrg = jsonParsedResponse.result

    // Get the complete list of package versions that are currently available in the DevHub
    rmsg = _.sh returnStdout: true, script: "sfdx force:package:version:list --json --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
    def allPackageVersionsAvailableInDevHub = jsonParse(rmsg).result

    def packageVersion

    for ( packageVersionsInstalledInScratchOrg in allPackageVersionsInstalledInScratchOrg ) {
        _.echo("packageVersionsInstalledInScratchOrg == ${packageVersionsInstalledInScratchOrg}")
        
        packageVersion = resolvePackageVersion( packageVersionsInstalledInScratchOrg, allPackageVersionsAvailableInDevHub )

        _.echo("dependency packageVersion resolved == ${packageVersion}")

        recordPackageVersionArtifact ( packageVersion )
    }

    // This is where the new package version that was just created will be captured as an artifact for Jenkins
    // this will be where the fingerprints of the build are created and then stored in Jenkins
    if ( this.sfdxNewPackageVersionId != null ) {

        // then a package was created.  Record its finger prints
        _.echo("finding all package versions for package ids found")
        rmsg = _.sh returnStdout: true, script: "sfdx force:package:version:list --packages ${this.sfdxNewPackage} --json --targetdevhubusername ${_.env.SFDX_DEV_HUB_USERNAME}"
        //printf rmsg

        def response = jsonParse( rmsg )
        
        def allPackageVersionsAvailable = response.result

        // loop through all allPackageVersionsAvailable until you find the new one with the this.sfdxNewPackageVersionId
        for ( packageVersionAvailable in allPackageVersionsAvailable ) {
            _.echo ("packageVersionAvailable == ${packageVersionAvailable}")
            _.echo ("this.sfdxNewPackage == ${this.sfdxNewPackage}")
            _.echo ("packageVersionAvailable.Package2Id == ${packageVersionAvailable.Package2Id}")
            _.echo ("this.sfdxNewPackageVersionId == ${this.sfdxNewPackageVersionId}")
            _.echo ("packageVersionAvailable.SubscriberPackageVersionId == ${packageVersionAvailable.SubscriberPackageVersionId}")
            if ( this.sfdxNewPackage == packageVersionAvailable.Package2Id && this.sfdxNewPackageVersionId == packageVersionAvailable.SubscriberPackageVersionId) {
                _.echo ("found a match")
                recordPackageVersionArtifact( packageVersionAvailable )
                break
            }
        }
    }
    
    _.archiveArtifacts allowEmptyArchive: true, artifacts: "${this.workingArtifactDirectory}/*.packageVersion", fingerprint: true, onlyIfSuccessful: true
  }

  // @NonCPS
  private Object jsonParse(def json) {
      new groovy.json.JsonSlurperClassic().parseText(json)
  }

  // @NonCPS
  private String resolveAliasToId( def alias, def sfdxPackage ) {
    _.echo("resolveAliasToId starting")
    _.echo("alias == ${alias}")
    if ( alias.empty || sfdxPackage == null || sfdxPackage.packageAliases == null ) {
      return null
    }
    for ( packageAliasKey in sfdxPackage.packageAliases.keySet() ) {
        _.echo("packageAliasKey == ${packageAliasKey}")
        // _.echo("packageAlias.containsKey(alias) == ${packageAlias.containsKey(alias)}")
        if ( alias == packageAliasKey ) {
            _.echo ("found a match")
            return sfdxPackage.packageAliases.get(alias)
        }
    }
  }

  private void recordPackageVersionArtifact( def packageVersion ) {
    def fileToFingerprint = "${this.workingArtifactDirectory}/${packageVersion.Package2Name.replaceAll(" ", "_")}-${packageVersion.Package2Id}--v${packageVersion.Version}"

    _.echo("packageVersion == ${packageVersion}")

    if ( packageVersion.Branch != null ) {
        fileToFingerprint += "-branch-${packageVersion.Branch.replaceAll("/", "-")}"
    } 

    fileToFingerprint += "-${packageVersion.SubscriberPackageVersionId}.packageVersion"
    
    _.echo("creating package version artifact for ${fileToFingerprint}")

    _.writeFile file: fileToFingerprint, text: "${packageVersion}"
  }

  private Object resolvePackageVersion( def packageVersionsInstalledInScratchOrg, def allPackageVersionsAvailableInDevHub ) {

    def result // this will be the allPackageVersionsAvailableInDevHub structure mentioned above.

    for ( packageVersionAvailableInDevHub in allPackageVersionsAvailableInDevHub ) {
        // _.echo ("packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId == ${packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId}")
        // _.echo ("packageVersionAvailableInDevHub.SubscriberPackageVersionId == ${packageVersionAvailableInDevHub.SubscriberPackageVersionId}")
        if ( packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId != null && packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId.equals(packageVersionAvailableInDevHub.SubscriberPackageVersionId) ) {
            result = packageVersionAvailableInDevHub
            break
        }
    } 
    
    // if packageVersionsInstalledInScratchOrg is not found in DevHub, then assemble as much of a response as possible.
    if ( result == null ) {
        result = [:]
        result.Package2Id = packageVersionsInstalledInScratchOrg.SubscriberPackageId
        result.Branch = null
        result.Version = packageVersionsInstalledInScratchOrg.SubscriberPackageVersionNumber
        result.SubscriberPackageVersionId = packageVersionsInstalledInScratchOrg.SubscriberPackageVersionId
        result.Package2Name = packageVersionsInstalledInScratchOrg.SubscriberPackageName
        result.NamespacePrefix = packageVersionsInstalledInScratchOrg.SubscriberPackageNamespace
    }

    _.echo ("result = ${result}")

    // the last line works as the return value
    return result
  }

          //  THIS DEFINITELY WORKS 
          // _.pipelineTriggers(
          //   [
          //     _.upstream(	
          //       upstreamProjects: "someUpstreamBuildProject/" + _.env.BRANCH_NAME.replaceAll("/", "%2F"),  threshold: hudson.model.Result.SUCCESS	
          //     )
          //   ]
          // )
  private Object processProjectTriggers() {
    def result = []

    if ( this.upstreamProjectsToTriggerFrom != null ) {

      def upstreamProjectName

      for ( anUpstreamProjectToTriggerFrom in this.upstreamProjectsToTriggerFrom ) {
        if ( !anUpstreamProjectToTriggerFrom.empty ) {
          upstreamProjectName = (this.upstreamProjectsToTriggerFromPrefix != null ? (this.upstreamProjectsToTriggerFromPrefix + '/') : '') + anUpstreamProjectToTriggerFrom
          // _.echo("adding upstream dependency on project ${anUpstreamProjectToTriggerFrom}")
          result << _.upstream(	upstreamProjects: upstreamProjectName + "/" + _.env.BRANCH_NAME.replaceAll("/", "%2F"),  threshold: hudson.model.Result.SUCCESS )
        }
      } 
    }

    return result
  }        

  private void debug( def message ) {
    if ( loggingDebugMessages ) {
      _.echo("DEBUG: ${message}")
    }
  }
}
