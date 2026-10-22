require('dotenv').config();
const { Pool } = require('pg');

// Cấu hình kết nối PostgreSQL
// Hỗ trợ cả DATABASE_URL hoặc các biến môi trường cấu hình chi tiết
const pool = new Pool(
  process.env.DATABASE_URL
    ? {
        connectionString: process.env.DATABASE_URL,
      }
    : {
        host: process.env.DB_HOST || 'localhost',
        port: parseInt(process.env.DB_PORT || '5432', 10),
        user: process.env.DB_USER || 'postgres',
        password: process.env.DB_PASSWORD || 'postgres',
        database: process.env.DB_NAME || 'mydb',
      }
);

// Lắng nghe các sự kiện của kết nối Pool
pool.on('error', (err) => {
  console.error('❌ Lỗi bất ngờ trên PostgreSQL pool:', err.message);
});

// Kiểm tra kết nối và tạo bảng users trước khi khởi động API
async function initDatabase() {
  const createTableQuery = `
    CREATE TABLE IF NOT EXISTS users (
      id SERIAL PRIMARY KEY,
      email VARCHAR(255) UNIQUE NOT NULL,
      password_hash VARCHAR(255) NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
    );
  `;

  const client = await pool.connect();
  try {
    console.log('✅ Đã thiết lập kết nối thành công tới cơ sở dữ liệu PostgreSQL!');
    await client.query(createTableQuery);
    console.log('✅ Bảng "users" đã sẵn sàng phục vụ dữ liệu.');
  } finally {
    client.release();
  }
}

module.exports = {
  pool,
  query: (text, params) => pool.query(text, params),
  initDatabase,
};
