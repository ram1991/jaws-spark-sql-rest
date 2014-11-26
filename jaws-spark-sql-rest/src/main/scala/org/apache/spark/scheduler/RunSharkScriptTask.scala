package org.apache.spark.scheduler

import traits.DAL
import server.MainActors
import server.Configuration
import server.LogsActor.PushLogs
import com.xpatterns.jaws.data.DTO.Result
import org.apache.commons.lang.time.DurationFormatUtils
import com.xpatterns.jaws.data.utils.QueryState
import implementation.HiveContextWrapper
import com.xpatterns.jaws.data.DTO.QueryMetaInfo

/**
 * Created by emaorhian
 */
class RunSharkScriptTask(dals: DAL, hqlScript: String, hiveContext: HiveContextWrapper, uuid: String, var isCanceled: Boolean, isLimited: Boolean, maxNumberOfResults: Long, hdfsConf: org.apache.hadoop.conf.Configuration, rddDestination:String) extends Runnable {

  override def run() {
    try {
      dals.loggingDal.setState(uuid, QueryState.IN_PROGRESS)
      dals.loggingDal.setScriptDetails(uuid, hqlScript)

      // parse the hql into independent commands
      val commands = HiveUtils.parseHql(hqlScript)
      var result: Result = null
      val nrOfCommands = commands.size

      var message = "There are " + nrOfCommands + " commands that need to be executed"
      Configuration.log4j.info(message)
      dals.loggingDal.addLog(uuid, "hql", System.currentTimeMillis(), message)
      MainActors.logsActor ! new PushLogs(uuid, message)

      val startTime = System.currentTimeMillis()

      // job group id used to identify these jobs when trying to cancel them.
      hiveContext.sparkContext.setJobGroup(uuid, "")

      // run each command except the last one
      for (commandIndex <- 0 to nrOfCommands - 2) {
        isCanceled match {
          case false => result = runCommand(commands(commandIndex), nrOfCommands, commandIndex, isLimited, false)
          case _ => Configuration.log4j.info("The task " + uuid + " was canceled")
        }
      }

      // the last command might need to be paginated
      isCanceled match {
        case false => result = runCommand(commands(nrOfCommands - 1), nrOfCommands, nrOfCommands - 1, isLimited, true)
        case _ => Configuration.log4j.info("The task " + uuid + " was canceled")
      }

      val executionTime = System.currentTimeMillis() - startTime
      var formattedDuration = DurationFormatUtils.formatDurationHMS(executionTime)

      Option(result) match {
        case None => Configuration.log4j.debug("[RunSharkScriptTask] result is null")
        case _ => dals.resultsDal.setResults(uuid, result)
      }

      message = "The total execution time was: " + formattedDuration + "!"
      dals.loggingDal.addLog(uuid, "hql", System.currentTimeMillis(), message)
      MainActors.logsActor ! new PushLogs(uuid, message)
      dals.loggingDal.setState(uuid, QueryState.DONE)

    } catch {
      case e: Exception => {
        Configuration.log4j.error(e.getStackTraceString)
        throw new RuntimeException(e)
      }
    }
  }

  def runCommand(command: String, nrOfCommands: Integer, commandIndex: Integer, isLimited: Boolean, isLastCommand: Boolean): Result = {
    var message = ""

    try {
     
      val result = HiveUtils.runCmdRdd(command, hiveContext, Configuration.numberOfResults.getOrElse("100").toInt, uuid, isLimited, maxNumberOfResults, isLastCommand, Configuration.rddDestinationIp.get, dals.loggingDal, hdfsConf, rddDestination)
      message = "Command progress : There were executed " + (commandIndex + 1) + " commands out of " + nrOfCommands
      Configuration.log4j.info(message)
      dals.loggingDal.addLog(uuid, "hql", System.currentTimeMillis(), message)
      MainActors.logsActor ! PushLogs(uuid, message)
      return result
    } catch {
      case e: Exception => {
        Configuration.log4j.error(e.getStackTraceString)
        dals.loggingDal.addLog(uuid, "hql", System.currentTimeMillis(), e.getStackTraceString)
        MainActors.logsActor ! PushLogs(uuid, e.getStackTraceString)
        dals.loggingDal.setState(uuid, QueryState.FAILED)
        dals.loggingDal.setMetaInfo(uuid, new QueryMetaInfo(0, maxNumberOfResults, 0, isLimited))

        throw new RuntimeException(e)
      }
    }
  }

  def setCanceled(canceled: Boolean) {
    isCanceled = canceled
  }

}