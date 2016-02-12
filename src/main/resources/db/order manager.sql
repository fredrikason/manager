--CREATE TABLE `orders` (
--  `order_id` int(11) NOT NULL,
--  `side` char(1) DEFAULT NULL,
--  `price` double DEFAULT NULL,
--  `account` varchar(20) DEFAULT NULL,
--  `ticker_id` int(11) DEFAULT NULL,
--  `quantity` int(11) DEFAULT NULL,
--  PRIMARY KEY (`order_id`)
--) ENGINE=InnoDB DEFAULT CHARSET=utf8

create table orders (order_id int primary key, side char(1), price double, account varchar(20), ticker_id int, quantity int);

--CREATE TABLE `instruments` (
--  `ticker_id` int(11) NOT NULL AUTO_INCREMENT,
--  `symbol` varchar(10) DEFAULT NULL,
--  PRIMARY KEY (`ticker_id`)
--) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8

create table instruments (ticker_id int auto_increment primary key, symbol varchar(10));

--CREATE TABLE `order_types` (
--  `side` char(1) NOT NULL,
--  `order_type` text,
--  PRIMARY KEY (`side`)
--) ENGINE=InnoDB DEFAULT CHARSET=utf8

create table order_types (side char(1) primary key, order_type text);
