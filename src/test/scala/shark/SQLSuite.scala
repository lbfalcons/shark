/*
 * Copyright (C) 2012 The Regents of The University California.
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package shark

import org.scalatest.BeforeAndAfterAll
import org.scalatest.FunSuite

import shark.api.QueryExecutionException


class SQLSuite extends FunSuite with BeforeAndAfterAll {

  val WAREHOUSE_PATH = TestUtils.getWarehousePath()
  val METASTORE_PATH = TestUtils.getMetastorePath()
  val MASTER = "local"

  var sc: SharkContext = _

  override def beforeAll() {
    sc = SharkEnv.initWithSharkContext("shark-sql-suite-testing", MASTER)

    sc.sql("set javax.jdo.option.ConnectionURL=jdbc:derby:;databaseName=" +
        METASTORE_PATH + ";create=true")
    sc.sql("set hive.metastore.warehouse.dir=" + WAREHOUSE_PATH)

    sc.sql("set shark.test.data.path=" + TestUtils.dataFilePath)

    // test
    sc.sql("drop table if exists test")
    sc.sql("CREATE TABLE test (key INT, val STRING)")
    sc.sql("LOAD DATA LOCAL INPATH '${hiveconf:shark.test.data.path}/kv1.txt' INTO TABLE test")
    sc.sql("drop table if exists test_cached")
    sc.sql("CREATE TABLE test_cached AS SELECT * FROM test")

    // test
    sc.sql("drop table if exists test_null")
    sc.sql("CREATE TABLE test_null (key INT, val STRING)")
    sc.sql("LOAD DATA LOCAL INPATH '${hiveconf:shark.test.data.path}/kv3.txt' INTO TABLE test_null")
    sc.sql("drop table if exists test_null_cached")
    sc.sql("CREATE TABLE test_null_cached AS SELECT * FROM test_null")

    // clicks
    sc.sql("drop table if exists clicks")
    sc.sql("""create table clicks (id int, click int)
      row format delimited fields terminated by '\t'""")
    sc.sql("""load data local inpath '${hiveconf:shark.test.data.path}/clicks.txt'
      OVERWRITE INTO TABLE clicks""")
    sc.sql("drop table if exists clicks_cached")
    sc.sql("create table clicks_cached as select * from clicks")

    // users
    sc.sql("drop table if exists users")
    sc.sql("""create table users (id int, name string)
      row format delimited fields terminated by '\t'""")
    sc.sql("""load data local inpath '${hiveconf:shark.test.data.path}/users.txt'
      OVERWRITE INTO TABLE users""")
    sc.sql("drop table if exists users_cached")
    sc.sql("create table users_cached as select * from users")
  }

  override def afterAll() {
    sc.stop()
    System.clearProperty("spark.driver.port")
  }

  private def expectSql(sql: String, expectedResults: Array[String], sort: Boolean = true) {
    val results = if (sort) sc.sql(sql).sortWith(_ < _) else sc.sql(sql)
    val expected = if (sort) expectedResults.sortWith(_ < _) else expectedResults
    assert(results.corresponds(expected)(_.equals(_)),
      "In SQL: " + sql + "\n" +
      "Expected: " + expected.mkString("\n") + "; got " + results.mkString("\n"))
  }

  // A shortcut for single row results.
  private def expectSql(sql: String, expectedResult: String) {
    expectSql(sql, Array(expectedResult))
  }

  //////////////////////////////////////////////////////////////////////////////
  // basic SQL
  //////////////////////////////////////////////////////////////////////////////
  test("count") {
    expectSql("select count(*) from test", "500")
    expectSql("select count(*) from test_cached", "500")
  }

  test("filter") {
    expectSql("select * from test where key=100 or key=497",
      Array("100\tval_100", "100\tval_100", "497\tval_497"))
    expectSql("select * from test_cached where key=100 or key=497",
      Array("100\tval_100", "100\tval_100", "497\tval_497"))
  }

  test("count distinct") {
    sc.sql("set mapred.reduce.tasks=3")
    expectSql("select count(distinct key) from test", "309")
    expectSql("select count(distinct key) from test_cached", "309")
    expectSql(
      """|SELECT substr(key,1,1), count(DISTINCT substr(val,5)) from test
         |GROUP BY substr(key,1,1)""".stripMargin,
      Array("0\t1", "1\t71", "2\t69", "3\t62", "4\t74", "5\t6", "6\t5", "7\t6", "8\t8", "9\t7"))
  }

  test("count bigint") {
    sc.sql("drop table if exists test_bigint")
    sc.sql("create table test_bigint (key bigint, val string)")
    sc.sql("""load data local inpath '${hiveconf:shark.test.data.path}/kv1.txt'
      OVERWRITE INTO TABLE test_bigint""")
    sc.sql("drop table if exists test_bigint_cached")
    sc.sql("create table test_bigint_cached as select * from test_bigint")
    expectSql("select val, count(*) from test_bigint_cached where key=484 group by val", "val_484\t1")
  }

  test("limit") {
    assert(sc.sql("select * from test limit 10").length === 10)
    assert(sc.sql("select * from test limit 501").length === 500)
    sc.sql("drop table if exists test_limit0")
    assert(sc.sql("select * from test limit 0").length === 0)
    assert(sc.sql("create table test_limit0 as select * from test limit 0").length === 0)
    assert(sc.sql("select * from test_limit0 limit 0").length === 0)
    assert(sc.sql("select * from test_limit0 limit 1").length === 0)
  }

  //////////////////////////////////////////////////////////////////////////////
  // sorting
  //////////////////////////////////////////////////////////////////////////////
  test("full order by") {
    expectSql("select * from users order by id", Array("1\tA", "2\tB", "3\tA"), false)
    expectSql("select * from users order by id desc", Array("3\tA", "2\tB", "1\tA"), false)
    expectSql("select * from users order by name, id", Array("1\tA", "3\tA", "2\tB"), false)
    expectSql("select * from users order by name desc, id desc", Array("2\tB", "3\tA", "1\tA"), false)
  }

  test("full order by with limit") {
    expectSql("select * from users order by id limit 2", Array("1\tA", "2\tB"), false)
    expectSql("select * from users order by id desc limit 2", Array("3\tA", "2\tB"), false)
    expectSql("select * from users order by name, id limit 2", Array("1\tA", "3\tA"), false)
    expectSql("select * from users order by name desc, id desc limit 2", Array("2\tB", "3\tA"), false)
  }

  //////////////////////////////////////////////////////////////////////////////
  // column pruning
  //////////////////////////////////////////////////////////////////////////////
  test("column pruning filters") {
    expectSql("select count(*) from test_cached where key > -1", "500")
  }

  test("column pruning group by") {
    expectSql("select key, count(*) from test_cached group by key order by key limit 1", "0\t3")
  }

  test("column pruning group by with single filter") {
    expectSql("select key, count(*) from test_cached where val='val_484' group by key", "484\t1")
  }

  test("column pruning aggregate function") {
    expectSql("select val, sum(key) from test_cached group by val order by val desc limit 1",
      "val_98\t196")
  }

  //////////////////////////////////////////////////////////////////////////////
  // map join
  //////////////////////////////////////////////////////////////////////////////
  test("map join") {
    expectSql("""select u.name, count(c.click) from clicks c join users u on (c.id = u.id)
      group by u.name having u.name='A'""",
      "A\t3")
  }

  test("map join2") {
    expectSql("select count(*) from clicks join users on (clicks.id = users.id)", "5")
  }

  //////////////////////////////////////////////////////////////////////////////
  // cache DDL
  //////////////////////////////////////////////////////////////////////////////
  test("insert into cached tables") {
    sc.sql("drop table if exists test1_cached")
    sc.sql("create table test1_cached as select * from test")
    expectSql("select count(*) from test1_cached", "500")
    sc.sql("insert into table test1_cached select * from test where key > -1 limit 499")
    expectSql("select count(*) from test1_cached", "999")
  }

  test("insert overwrite") {
    sc.sql("drop table if exists test2_cached")
    sc.sql("create table test2_cached as select * from test")
    expectSql("select count(*) from test2_cached", "500")
    sc.sql("insert overwrite table test2_cached select * from test where key > -1 limit 499")
    expectSql("select count(*) from test2_cached", "499")
  }

  test("error when attempting to update cached table(s) using command with multiple INSERTs") {
    sc.sql("drop table if exists multi_insert_test")
    sc.sql("drop table if exists multi_insert_test_cached")
    sc.sql("create table multi_insert_test as select * from test")
    sc.sql("create table multi_insert_test_cached as select * from test")
    intercept[QueryExecutionException] {
      sc.sql("""from test
        insert into table multi_insert_test select *
        insert into table multi_insert_test_cached select *""")
    }
  }

  // This test is flaky on some systems...
  // test("drop partition") {
  //   sc.sql("create table foo_cached(key int, val string) partitioned by (dt string)")
  //   sc.sql("insert overwrite table foo_cached partition(dt='100') select * from test")
  //   expectSql("select count(*) from foo_cached", "500")
  //   sc.sql("alter table foo_cached drop partition(dt='100')")
  //   expectSql("select count(*) from foo_cached", "0")
  // }

  test("create cached table with table properties") {
    sc.sql("drop table if exists ctas_tbl_props")
    sc.sql("""create table ctas_tbl_props TBLPROPERTIES ('shark.cache'='true') as
      select * from test""")
    assert(SharkEnv.memoryMetadataManager.contains("ctas_tbl_props"))
    expectSql("select * from ctas_tbl_props where key=407", "407\tval_407")
  }

  test("cached tables with complex types") {
    sc.sql("drop table if exists test_complex_types")
    sc.sql("drop table if exists test_complex_types_cached")
    sc.sql("""CREATE TABLE test_complex_types (
      a STRING, b ARRAY<STRING>, c ARRAY<MAP<STRING,STRING>>, d MAP<STRING,ARRAY<STRING>>)""")
    sc.sql("""load data local inpath '${hiveconf:shark.test.data.path}/create_nested_type.txt'
      overwrite into table test_complex_types""")
    sc.sql("""create table test_complex_types_cached TBLPROPERTIES ("shark.cache" = "true") as
      select * from test_complex_types""")
    expectSql("select a from test_complex_types_cached where a = 'a0'", """a0""")
    expectSql("select b from test_complex_types_cached where a = 'a0'", """["b00","b01"]""")
    expectSql("select c from test_complex_types_cached where a = 'a0'",
      """[{"c001":"C001","c002":"C002"},{"c011":null,"c012":"C012"}]""")
    expectSql("select d from test_complex_types_cached where a = 'a0'",
      """{"d01":["d011","d012"],"d02":["d021","d022"]}""")
    assert(SharkEnv.memoryMetadataManager.contains("test_complex_types_cached"))
  }

  test("disable caching by default") {
    sc.sql("set shark.cache.flag.checkTableName=false")
    sc.sql("drop table if exists should_not_be_cached")
    sc.sql("create table should_not_be_cached as select * from test")
    expectSql("select key from should_not_be_cached where key = 407", "407")
    assert(!SharkEnv.memoryMetadataManager.contains("should_not_be_cached"))
    sc.sql("set shark.cache.flag.checkTableName=true")
  }

  test("cached table name should be case-insensitive") {
    sc.sql("drop table if exists sharkTest5Cached")
    sc.sql("""create table sharkTest5Cached TBLPROPERTIES ("shark.cache" = "true") as
      select * from test""")
    expectSql("select val from sharktest5Cached where key = 407", "val_407")
    assert(SharkEnv.memoryMetadataManager.contains("sharkTest5Cached"))
  }
  
  test("dropping cached tables should clean up RDDs") {
    sc.sql("drop table if exists sharkTest5Cached")
    sc.sql("""create table sharkTest5Cached TBLPROPERTIES ("shark.cache" = "true") as
      select * from test""")
    sc.sql("drop table sharkTest5Cached")
    assert(!SharkEnv.memoryMetadataManager.contains("sharkTest5Cached"))
  }

  //////////////////////////////////////////////////////////////////////////////
  // SharkContext APIs (e.g. sql2rdd, sql)
  //////////////////////////////////////////////////////////////////////////////

  test("sql max number of rows returned") {
    assert(sc.sql("select * from test").size === 500)
    assert(sc.sql("select * from test", 100).size === 100)
  }

  test("sql2rdd") {
    var rdd = sc.sql2rdd("select * from test")
    assert(rdd.count === 500)
    rdd = sc.sql2rdd("select * from test_cached")
    assert(rdd.count === 500)
    val collected = rdd.map(r => r.getInt(0)).collect().sortWith(_ < _)
    assert(collected(0) === 0)
    assert(collected(499) === 498)
    assert(collected.size === 500)
  }

  test("null values in sql2rdd") {
    val nullsRdd = sc.sql2rdd("select * from test_null where key is null")
    val nulls = nullsRdd.map(r => r.getInt(0)).collect()
    assert(nulls(0) === null)
    assert(nulls.size === 10)
  }

  test("sql exception") {
    val e = intercept[QueryExecutionException] { sc.sql("asdfasdfasdfasdf") }
    e.getMessage.contains("semantic")
  }

  test("sql2rdd exception") {
    val e = intercept[QueryExecutionException] { sc.sql2rdd("asdfasdfasdfasdf") }
    e.getMessage.contains("semantic")
  }
}
