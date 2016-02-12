package order.actors

import org.scalatest._

class OrderManagerTest extends FlatSpec with Matchers {

  "An order" should "be processed" in {
    val manager = new OrderManager
    manager.process should be ("Processed order 12345")
  }
}