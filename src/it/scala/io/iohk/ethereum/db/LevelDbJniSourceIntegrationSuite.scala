package io.iohk.ethereum.db

import io.iohk.ethereum.db.dataSource.{ LevelDBDataSource, LevelDbConfig }
import org.scalatest.FlatSpec

class LevelDbJniSourceIntegrationSuite extends FlatSpec with DataSourceIntegrationTestBehavior {

    private def createDataSource(dataSourcePath: String) = LevelDBDataSource(new LevelDbConfig {
      override val verifyChecksums: Boolean = true
      override val paranoidChecks: Boolean = true
      override val createIfMissing: Boolean = true
      override val path: String = dataSourcePath
      override val native: Boolean = true
      override val maxOpenFiles: Int = 32
    })

    it should behave like dataSource(createDataSource)

}