package org.neo4j.driver.internal.value

import org.apache.log4j.Logger

/**
  * Created by bluejoe on 2017/10/14.
  */
trait Logging {
  protected lazy val logger = Logger.getLogger(this.getClass);
}
