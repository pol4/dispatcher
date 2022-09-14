package ot.dispatcher

import org.apache.kafka.clients.consumer.{ConsumerRecords, KafkaConsumer}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.errors.WakeupException
import ot.dispatcher.kafka.context.CommandsContainer
import play.api.libs.json.{JsValue, Json}

import java.time.Duration
import java.util
import java.util.Properties
import scala.collection.JavaConverters._

class SuperKafkaConnector(val port: Int) {
  var consumer: KafkaConsumer[String, String] = createConsumer

  var producer: KafkaProducer[String, String] = createProducer

  private def createConsumer(): KafkaConsumer[String, String] = {

    val props = new Properties
    props.put(s"bootstrap.servers", s"localhost:${port}")
    props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    props.put("auto.offset.reset", "latest")
    props.put("group.id", "consumer-group")
    new KafkaConsumer[String, String](props)
  }

  private def subscribeConsumer(nodeUuid: String): Unit = {
    val topic = nodeUuid + "_job"
    consumer.subscribe(util.Arrays.asList(topic))
  }

  def getNewCommands(nodeUuid: String): Unit = {
    println("start commands getting")
    subscribeConsumer(nodeUuid)
    try {
      while (true) {
        val records: ConsumerRecords[String, String] = consumer.poll(Duration.ofMillis(100))
        for (record <- records.asScala) {
          CommandsContainer.syncValues.add(Json.parse(record.value()))
        }
        for (sv <- CommandsContainer.syncValues.toArray) {
          val svIns = sv.asInstanceOf[JsValue]
          if (CommandsContainer.changedValues.contains(svIns)) {
            CommandsContainer.syncValues.remove(svIns)
            CommandsContainer.changedValues.remove(CommandsContainer.changedValues.indexOf(svIns))
          }
        }
      }
    } catch {
      case e: WakeupException =>
    } finally {
    }
  }

  private def createProducer(): KafkaProducer[String, String] = {
    val props = new Properties
    props.put("bootstrap.servers", "localhost:9092")
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    new KafkaProducer[String, String](props)
  }

  def sendMessage(topic: String, key: String, value: String): Boolean = {
    try {
      val record = new ProducerRecord[String, String](topic, key, value)
      producer.send(record).get()
      true
    } catch {
      case e: Exception => {
        e.printStackTrace()
        false
      }
    }
  }
}
