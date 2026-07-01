ALTER TABLE items ADD COLUMN price NUMERIC(10, 2) DEFAULT 0;

-- Seed a test row
INSERT INTO items (id, name, price) VALUES ('seed-1', 'Seed Item', 9.99);
