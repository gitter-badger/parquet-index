/*
 * Copyright 2016 Lightcopy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.internal

import java.util.{Collections, HashMap, NoSuchElementException, Properties}

import scala.collection.JavaConverters._
import scala.collection.immutable

import org.apache.spark.internal.config._
import org.apache.spark.sql.SparkSession

/**
 * All available configuration for index functionality, similar to `SQLConf`.
 * Used as part of the metastore.
 */
private[spark] object IndexConf {
  // readonly/write-once map to restore conf entries
  private val confEntries =
    Collections.synchronizedMap(new HashMap[String, ConfigEntry[_]]())

  private[internal] def register(entry: ConfigEntry[_]): Unit = confEntries.synchronized {
    require(!confEntries.containsKey(entry.key),
      s"Duplicate ConfigEntry. ${entry.key} has been registered, settings $confEntries")
    confEntries.put(entry.key, entry)
  }

  private object IndexConfigBuilder {
    def apply(key: String): ConfigBuilder = {
      new ConfigBuilder(key).onCreate(register)
    }
  }

  /** Get new configuration by extracting registered keys from Spark session */
  def newConf(sparkSession: SparkSession): IndexConf = {
    val conf = new IndexConf()
    for (key <- confEntries.keySet().asScala) {
      sparkSession.conf.getOption(key) match {
        case Some(value) => conf.setConfString(key, value)
        case None => // do nothing
      }
    }
    conf
  }

  // metastore location (root directory in case of file system)
  // will be created, if it does not exist
  val METASTORE_LOCATION = IndexConfigBuilder("spark.sql.index.metastore").
    doc("Metastore location or root directory to store index information, will be created " +
      "if path does not exist").
    stringConf.
    createWithDefault("")

  // option to enable/disable bloom filters for Parquet index
  val PARQUET_BLOOM_FILTER_ENABLED = IndexConfigBuilder("spark.sql.index.parquet.bloom.enabled").
    doc("When set to true writes bloom filters for indexed columns when creating table index").
    booleanConf.
    createWithDefault(false)
}

private[spark] class IndexConf extends Serializable {
  import IndexConf._

  /** Only low degree of contention is expected for conf, thus NOT using ConcurrentHashMap. */
  @transient private[internal] val settings = java.util.Collections.synchronizedMap(
    new java.util.HashMap[String, String]())

  //////////////////////////////////////////////////////////////
  // == Index configuration ==
  //////////////////////////////////////////////////////////////

  def metastoreLocation: String = getConf(METASTORE_LOCATION)

  def parquetBloomFilterEnabled: Boolean = getConf(PARQUET_BLOOM_FILTER_ENABLED)

  //////////////////////////////////////////////////////////////
  // == Configuration functionality methods ==
  //////////////////////////////////////////////////////////////

  /**
   * Update value in configuration.
   */
  private def setConfWithCheck(key: String, value: String): Unit = {
    settings.put(key, value)
  }

  /** Set the given configuration property using a `string` value. */
  def setConfString(key: String, value: String): Unit = {
    require(key != null, "key cannot be null")
    require(value != null, s"value cannot be null for key: $key")
    val entry = confEntries.get(key)
    if (entry != null) {
      entry.valueConverter(value)
    }
    setConfWithCheck(key, value)
  }

  /** Set the given index configuration property. */
  def setConf[T](entry: ConfigEntry[T], value: T): Unit = {
    require(entry != null, "entry cannot be null")
    require(value != null, s"value cannot be null for key: ${entry.key}")
    setConfWithCheck(entry.key, entry.stringConverter(value))
  }

  /**
   * Return the value of index configuration entry property for the given key. If the key is not
   * set yet, return `defaultValue` in `ConfigEntry`.
   */
  def getConf[T](entry: ConfigEntry[T]): T = {
    Option(settings.get(entry.key)).map(entry.valueConverter).orElse(entry.defaultValue).
      getOrElse(throw new NoSuchElementException(entry.key))
  }

  /**
   * Return all the configuration properties that have been set (i.e. not the default).
   * This creates a new copy of the config properties in the form of a Map.
   */
  def getAllConfs: immutable.Map[String, String] =
    settings.synchronized { settings.asScala.toMap }

  /**
   * Unset configuration for a given key.
   */
  def unsetConf(key: String): Unit = {
    settings.remove(key)
  }

  /**
   * Unset configuration for a given key of configuration entry.
   */
  def unsetConf(entry: ConfigEntry[_]): Unit = {
    settings.remove(entry.key)
  }

  /**
   * Clear configuration.
   */
  def clear(): Unit = {
    settings.clear()
  }
}
