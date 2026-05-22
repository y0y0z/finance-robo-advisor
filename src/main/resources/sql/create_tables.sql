-- User表
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL
);

-- Asset表
CREATE TABLE assets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    purchase_price DECIMAL(19, 4) NOT NULL,
    purchase_date TIMESTAMP NOT NULL,
    current_price DECIMAL(19, 4) NOT NULL,
    total_value DECIMAL(19, 4) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Stock表
CREATE TABLE stocks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    price DECIMAL(19, 4) NOT NULL,
    price_change DECIMAL(19, 4) NOT NULL,
    change_percent DECIMAL(19, 4) NOT NULL,
    update_time TIMESTAMP NOT NULL,
    market VARCHAR(255) NOT NULL,
    pe DECIMAL(19, 4),
    pb DECIMAL(19, 4),
    nav DECIMAL(19, 4)
);

-- News表
CREATE TABLE news (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    source VARCHAR(255) NOT NULL,
    publish_time TIMESTAMP NOT NULL,
    category VARCHAR(255) NOT NULL,
    url VARCHAR(255) NOT NULL
);
