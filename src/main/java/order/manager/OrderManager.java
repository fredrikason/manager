package order.manager;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jms.config.JmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;
import org.springframework.jms.listener.SimpleMessageListenerContainer;

import javax.jms.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
public class OrderManager {

    static class Message {
        String xml;

        Message(String xml) {
            this.xml = xml;
        }
    }

    static class Instrument {
        int tickerId;
        String symbol;

        Instrument(int tickerId, String symbol) {
            this.tickerId = tickerId;
            this.symbol = symbol;
        }

        @Override
        public String toString() {
            return "Instrument{" +
                    "tickerId='" + tickerId + '\'' +
                    ", symbol='" + symbol + '\'' +
                    '}';
        }
    }

    static class Order {

        int orderId;
        int side;
        double price;
        String account;
        int quantity;
        Instrument instrument;

        Order(int orderId, int side, double price, String account, int quantity, Instrument instrument) {
            this.orderId = orderId;
            this.side = side;
            this.price = price;
            this.account = account;
            this.quantity = quantity;
            this.instrument = instrument;
        }

        @Override
        public String toString() {
            return "Order{" +
                    "orderId='" + orderId + '\'' +
                    ", side=" + side +
                    ", price=" + price +
                    ", account='" + account + '\'' +
                    ", quantity=" + quantity +
                    ", instrument=" + instrument +
                    '}';
        }
    }


    static JmsTemplate jmsTemplate;

    @Autowired
    void setJmsTemplate(JmsTemplate jmsTemplate) {
        OrderManager.jmsTemplate = jmsTemplate;
    }

    @Bean
    static ConnectionFactory connectionFactory() {
        return new ActiveMQConnectionFactory("tcp://localhost:61616");
    }

    @Bean
    static JmsListenerContainerFactory<?> jmsContainerFactory(ConnectionFactory connectionFactory) {
        SimpleJmsListenerContainerFactory factory = new SimpleJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        return factory;
    }

    static class MessageReceiver implements MessageListener {

        final ActorRef router;
        final SimpleMessageListenerContainer container;

        MessageReceiver(ActorRef router) {
            this.router = router;
            this.container = new SimpleMessageListenerContainer();
            container.setConnectionFactory(connectionFactory());
            container.setDestinationName("order.manager");
            container.setMessageListener(this);
        }

        void start() {
            container.start();
        }

        public void onMessage(javax.jms.Message message) {
            if (message instanceof TextMessage) {
                TextMessage textMessage = (TextMessage) message;
                try {
                    String msg = textMessage.getText();
                    log.info("Received message: \n" + msg);
                    router.tell(new Message(msg), ActorRef.noSender());
                } catch (JMSException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    static class MessageSender implements MessageCreator {

        @Override
        public javax.jms.Message createMessage(Session session) throws JMSException {
            try {
                File file = new File(getClass().getClassLoader().getResource("data/orders.xml").getFile());
                return session.createTextMessage(new String(Files.readAllBytes(Paths.get(file.getPath()))));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        void send() {
            jmsTemplate.send("order.manager", this);
        }
    }

    static class MessageTranslator extends UntypedActor {

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Message) {
                Message work = (Message) message;
                for (Order order : translate(work.xml)) {
                    getSender().tell(order, getSelf());
                }
            } else {
                unhandled(message);
            }
        }

        List<Order> translate(String xml) throws IOException, JDOMException {
            SAXBuilder builder = new SAXBuilder();
            Document doc = builder.build(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getRootElement();
            List<Order> orders = new ArrayList<>();

            for (Element order : root.getChildren("order")) {
                int orderId = Integer.valueOf(order.getAttributeValue("order_id"));
                int side = Integer.valueOf(order.getAttributeValue("side"));
                double price = Double.valueOf(order.getAttributeValue("price"));
                String account = order.getAttributeValue("account");
                int quantity = Integer.valueOf(order.getAttributeValue("quantity"));
                Element instrument = order.getChild("instrument");
                int tickerId = Integer.valueOf(instrument.getAttributeValue("ticker_id"));
                String symbol = instrument.getAttributeValue("symbol");
                orders.add(new Order(orderId, side, price, account, quantity, new Instrument(tickerId, symbol)));
            }
            return orders;
        }
    }

    static class MessageRouter extends UntypedActor {

        final ActorRef translator;

        MessageRouter(ActorRef translator) {
            this.translator = translator;
        }

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Message) {
                translator.tell(message, getSelf());
            } else if (message instanceof Order) {
                Order order = (Order) message;
                final ActorRef processor = getContext().actorOf(Props.create(OrderProcessor.class), "processor-" + order.orderId);
                processor.tell(order, getSelf());
            } else {
                unhandled(message);
            }
        }
    }

    static JdbcTemplate jdbcTemplate;


    @Autowired
    void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        OrderManager.jdbcTemplate = jdbcTemplate;
    }

    static class OrderProcessor extends UntypedActor {

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Order) {
                Order order = (Order) message;
                jdbcTemplate.update("insert into orders (order_id, side, price, account, ticker_id, quantity) values (?, ?, ?, ?, ?, ?)" ,
                        new PreparedStatementSetter() {
                            @Override
                            public void setValues(PreparedStatement ps) throws SQLException {
                                ps.setInt(1, order.orderId);
                                ps.setInt(2, order.side);
                                ps.setDouble(3, order.price);
                                ps.setString(4, order.account);
                                ps.setInt(5, order.instrument.tickerId);
                                ps.setInt(6, order.quantity);
                            }
                        });
                log.info("Processed " + order);
            } else {
                unhandled(message);
            }
        }
    }

    static final Logger log = LoggerFactory.getLogger(OrderManager.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(OrderManager.class, args);

        ActorSystem system = ActorSystem.create("OrderSystem");

        final ActorRef translator = system.actorOf(Props.create(MessageTranslator.class), "translator");

        final ActorRef router = system.actorOf(Props.create(MessageRouter.class, translator), "router");

        MessageReceiver receiver = new MessageReceiver(router);
        receiver.start();

        MessageSender sender = new MessageSender();
        sender.send();
    }
}
