require('dotenv').config();
const express = require('express');
const cors = require('cors');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { pool, initDatabase } = require('./db');

const app = express();
const PORT = process.env.PORT || 5000;
const JWT_SECRET = process.env.JWT_SECRET;

if (!JWT_SECRET) {
  throw new Error('Thiếu JWT_SECRET trong cấu hình môi trường.');
}

// Middleware
app.use(cors());
app.use(express.json());

// Email validation regex theo chuẩn phổ biến RFC 5322
const EMAIL_REGEX = /^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/;

// Cấu hình quy tắc kiểm tra mật khẩu
const PASSWORD_RULES = {
  MIN_LENGTH: 8,
  MAX_LENGTH: 32,
  HAS_UPPERCASE: /[A-Z]/,
  HAS_NUMBER: /[0-9]/,
  HAS_SPECIAL_CHAR: /[\p{P}\p{S}]/u,
};

function validatePassword(password) {
  if (typeof password !== 'string') {
    return 'Mật khẩu không hợp lệ.';
  }
  if (password.length < PASSWORD_RULES.MIN_LENGTH || password.length > PASSWORD_RULES.MAX_LENGTH) {
    return `Mật khẩu phải có độ dài từ ${PASSWORD_RULES.MIN_LENGTH} đến ${PASSWORD_RULES.MAX_LENGTH} ký tự.`;
  }
  if (!PASSWORD_RULES.HAS_UPPERCASE.test(password)) {
    return 'Mật khẩu phải chứa ít nhất 1 chữ cái in hoa (A-Z).';
  }
  if (!PASSWORD_RULES.HAS_NUMBER.test(password)) {
    return 'Mật khẩu phải chứa ít nhất 1 chữ số (0-9).';
  }
  if (!PASSWORD_RULES.HAS_SPECIAL_CHAR.test(password)) {
    return 'Mật khẩu phải chứa ít nhất 1 ký tự đặc biệt (ví dụ: !@#$%^&*...).';
  }
  return null;
}



// Middleware xác thực JWT token
function authenticateToken(req, res, next) {
  const authHeader = req.headers['authorization'];
  const token = authHeader && authHeader.split(' ')[1];

  if (!token) {
    return res.status(401).json({ error: 'Không tìm thấy token xác thực.' });
  }

  jwt.verify(token, JWT_SECRET, (err, decodedUser) => {
    if (err) {
      return res.status(403).json({ error: 'Token không hợp lệ hoặc đã hết hạn.' });
    }
    req.user = decodedUser;
    next();
  });
}

// ====================== REST API ENDPOINTS ====================== //

// 1. Kiểm tra trạng thái API
app.get('/api/health', async (req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ status: 'OK', database: 'connected' });
  } catch (err) {
    res.status(500).json({ status: 'ERROR', database: 'disconnected', details: err.message });
  }
});

// 2. API Đăng ký (Register)
app.post('/api/register', async (req, res) => {
  try {
    const body = req.body && typeof req.body === 'object' ? req.body : {};
    const { email, password, confirmPassword } = body;

    // Kiểm tra trường bắt buộc
    if (
      typeof email !== 'string' || !email.trim() ||
      typeof password !== 'string' || !password ||
      typeof confirmPassword !== 'string' || !confirmPassword
    ) {
      return res.status(400).json({ error: 'Vui lòng điền đầy đủ email, mật khẩu và xác nhận mật khẩu.' });
    }

    const normalizedEmail = email.trim().toLowerCase();

    // Kiểm tra định dạng email
    if (!EMAIL_REGEX.test(normalizedEmail)) {
      return res.status(400).json({ error: 'Định dạng email không hợp lệ (Ví dụ: user@example.com).' });
    }

    // Kiểm tra độ an toàn mật khẩu
    const passwordError = validatePassword(password);
    if (passwordError) {
      return res.status(400).json({ error: passwordError });
    }

    // Kiểm tra xác nhận mật khẩu trùng khớp
    if (password !== confirmPassword) {
      return res.status(400).json({ error: 'Mật khẩu xác nhận không khớp với mật khẩu đã nhập.' });
    }

    // Kiểm tra trùng email trong database
    const existingUser = await pool.query('SELECT id FROM users WHERE email = $1', [normalizedEmail]);
    if (existingUser.rows.length > 0) {
      return res.status(409).json({ error: 'Email này đã được sử dụng. Vui lòng chọn một email khác.' });
    }

    // Mã hóa mật khẩu
    const saltRounds = 10;
    const passwordHash = await bcrypt.hash(password, saltRounds);

    // Lưu vào cơ sở dữ liệu
    const insertResult = await pool.query(
      'INSERT INTO users (email, password_hash) VALUES ($1, $2) RETURNING id, email, created_at',
      [normalizedEmail, passwordHash]
    );

    const newUser = insertResult.rows[0];

    return res.status(201).json({
      message: 'Đăng ký tài khoản thành công!',
      user: {
        id: newUser.id,
        email: newUser.email,
        createdAt: newUser.created_at,
      },
    });
  } catch (err) {
    if (err.code === '23505') {
      // Postgres unique_violation error
      return res.status(409).json({ error: 'Email này đã được sử dụng. Vui lòng chọn một email khác.' });
    }
    console.error('Lỗi khi đăng ký:', err);
    return res.status(500).json({ error: 'Đã có lỗi xảy ra ở máy chủ. Vui lòng thử lại sau.' });
  }
});

// 3. API Đăng nhập (Login)
app.post('/api/login', async (req, res) => {
  try {
    const body = req.body && typeof req.body === 'object' ? req.body : {};
    const { email, password } = body;

    // Kiểm tra trường bắt buộc
    if (typeof email !== 'string' || !email.trim() || typeof password !== 'string' || !password) {
      return res.status(400).json({ error: 'Vui lòng nhập cả email và mật khẩu.' });
    }

    const normalizedEmail = email.trim().toLowerCase();

    // Kiểm tra định dạng email
    if (!EMAIL_REGEX.test(normalizedEmail)) {
      return res.status(400).json({ error: 'Định dạng email không hợp lệ.' });
    }

    // Tìm người dùng theo email
    const result = await pool.query('SELECT * FROM users WHERE email = $1', [normalizedEmail]);
    if (result.rows.length === 0) {
      return res.status(401).json({ error: 'Email hoặc mật khẩu không chính xác.' });
    }

    const user = result.rows[0];

    // Đối chiếu mật khẩu đã hash
    const isPasswordMatch = await bcrypt.compare(password, user.password_hash);
    if (!isPasswordMatch) {
      return res.status(401).json({ error: 'Email hoặc mật khẩu không chính xác.' });
    }

    // Tạo JWT token
    const token = jwt.sign(
      { id: user.id, email: user.email },
      JWT_SECRET,
      { expiresIn: '24h' }
    );

    return res.status(200).json({
      message: 'Đăng nhập thành công!',
      token,
      user: {
        id: user.id,
        email: user.email,
        createdAt: user.created_at,
      },
    });
  } catch (err) {
    console.error('Lỗi khi đăng nhập:', err);
    return res.status(500).json({ error: 'Đã có lỗi xảy ra ở máy chủ. Vui lòng thử lại sau.' });
  }
});

// 4. API Lấy thông tin người dùng hiện tại (Profile / Session)
app.get('/api/me', authenticateToken, async (req, res) => {
  try {
    const result = await pool.query('SELECT id, email, created_at FROM users WHERE id = $1', [req.user.id]);
    if (result.rows.length === 0) {
      return res.status(404).json({ error: 'Người dùng không tồn tại.' });
    }
    const user = result.rows[0];
    res.json({
      user: {
        id: user.id,
        email: user.email,
        createdAt: user.created_at,
      },
    });
  } catch (err) {
    console.error('Lỗi khi lấy thông tin người dùng:', err);
    res.status(500).json({ error: 'Lỗi máy chủ khi lấy thông tin tài khoản.' });
  }
});

async function startServer() {
  await initDatabase();
  app.listen(PORT, () => {
    console.log(`🚀 Backend REST API đang chạy tại http://localhost:${PORT}`);
  });
}

startServer().catch(async (err) => {
  console.error('❌ Không thể khởi động backend:', err.message);
  try {
    await pool.end();
  } catch (shutdownError) {
    console.error('❌ Không thể đóng kết nối PostgreSQL:', shutdownError.message);
  }
  process.exitCode = 1;
});
