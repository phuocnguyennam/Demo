import React, { useState, useEffect } from 'react';

// Regex kiểm tra định dạng email theo chuẩn phổ thông
const EMAIL_REGEX = /^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/;

// Cấu hình quy tắc mật khẩu
export const PASSWORD_RULES = {
  MIN_LENGTH: 8,
  MAX_LENGTH: 32,
  HAS_UPPERCASE: /[A-Z]/,
  HAS_NUMBER: /[0-9]/,
  HAS_SPECIAL_CHAR: /[\p{P}\p{S}]/u,
};

export const checkPasswordCriteria = (password) => {
  const pwd = password || '';
  return {
    isLengthValid: pwd.length >= PASSWORD_RULES.MIN_LENGTH && pwd.length <= PASSWORD_RULES.MAX_LENGTH,
    hasUppercase: PASSWORD_RULES.HAS_UPPERCASE.test(pwd),
    hasNumber: PASSWORD_RULES.HAS_NUMBER.test(pwd),
    hasSpecialChar: PASSWORD_RULES.HAS_SPECIAL_CHAR.test(pwd),
  };
};

export const validatePassword = (password) => {
  if (!password) {
    return 'Vui lòng nhập mật khẩu.';
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
};

export default function App() {
  // Quản lý màn hình hiển thị: 'login' | 'register' | 'hello'
  const [view, setView] = useState('login');

  // Thông tin người dùng đăng nhập hiện tại
  const [currentUser, setCurrentUser] = useState(null);
  const [isInitializing, setIsInitializing] = useState(true);

  // Form Đăng nhập
  const [loginEmail, setLoginEmail] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [showLoginPassword, setShowLoginPassword] = useState(false);

  // Form Đăng ký
  const [regEmail, setRegEmail] = useState('');
  const [regPassword, setRegPassword] = useState('');
  const [regConfirmPassword, setRegConfirmPassword] = useState('');
  const [showRegPassword, setShowRegPassword] = useState(false);

  // Trạng thái kiểm tra tiêu chí mật khẩu trực quan theo thời gian thực
  const passwordCriteria = checkPasswordCriteria(regPassword);

  // Trạng thái thông báo lỗi / thành công / loading
  const [errorMsg, setErrorMsg] = useState('');
  const [successMsg, setSuccessMsg] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  // Tự động kiểm tra token lưu trong localStorage khi tải trang
  useEffect(() => {
    const checkSavedSession = async () => {
      const savedToken = localStorage.getItem('auth_token');
      if (!savedToken) {
        setIsInitializing(false);
        return;
      }

      try {
        const response = await fetch('/api/me', {
          headers: {
            Authorization: `Bearer ${savedToken}`,
          },
        });

        if (response.ok) {
          const data = await response.json();
          setCurrentUser(data.user);
          setView('hello');
        } else {
          localStorage.removeItem('auth_token');
        }
      } catch (err) {
        console.warn('Lỗi khi khôi phục phiên đăng nhập:', err);
      } finally {
        setIsInitializing(false);
      }
    };

    checkSavedSession();
  }, []);

  // Xóa các thông báo khi chuyển đổi tab
  const switchView = (targetView) => {
    setErrorMsg('');
    setSuccessMsg('');
    setView(targetView);
  };

  // ==================== XỬ LÝ ĐĂNG KÝ ====================
  const handleRegister = async (e) => {
    e.preventDefault();
    setErrorMsg('');
    setSuccessMsg('');

    const emailTrimmed = regEmail.trim();

    // 1. Kiểm tra không được để trống
    if (!emailTrimmed || !regPassword || !regConfirmPassword) {
      setErrorMsg('Vui lòng điền đầy đủ cả 3 ô thông tin.');
      return;
    }

    // 2. Kiểm tra định dạng email
    if (!EMAIL_REGEX.test(emailTrimmed)) {
      setErrorMsg('Định dạng email không hợp lệ (Ví dụ hợp lệ: name@example.com).');
      return;
    }

    // 3. Kiểm tra độ an toàn mật khẩu
    const passwordError = validatePassword(regPassword);
    if (passwordError) {
      setErrorMsg(passwordError);
      return;
    }

    // 4. Kiểm tra xác nhận mật khẩu
    if (regPassword !== regConfirmPassword) {
      setErrorMsg('Mật khẩu xác nhận không khớp với mật khẩu đã nhập.');
      return;
    }

    setIsLoading(true);

    try {
      const response = await fetch('/api/register', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          email: emailTrimmed,
          password: regPassword,
          confirmPassword: regConfirmPassword,
        }),
      });

      const data = await response.json();

      if (!response.ok) {
        setErrorMsg(data.error || 'Đăng ký thất bại. Vui lòng thử lại.');
      } else {
        setSuccessMsg('Chúc mừng bạn đã đăng ký thành công! Hãy đăng nhập ngay.');
        // Điền sẵn email vừa đăng ký sang form login
        setLoginEmail(emailTrimmed);
        setLoginPassword('');
        setRegEmail('');
        setRegPassword('');
        setRegConfirmPassword('');
        // Chuyển sang màn hình đăng nhập
        setView('login');
      }
    } catch (err) {
      setErrorMsg('Không thể kết nối tới máy chủ backend. Vui lòng kiểm tra xem backend đã chạy chưa.');
    } finally {
      setIsLoading(false);
    }
  };

  // ==================== XỬ LÝ ĐĂNG NHẬP ====================
  const handleLogin = async (e) => {
    e.preventDefault();
    setErrorMsg('');
    setSuccessMsg('');

    const emailTrimmed = loginEmail.trim();

    // 1. Kiểm tra để trống
    if (!emailTrimmed || !loginPassword) {
      setErrorMsg('Vui lòng nhập đầy đủ email và mật khẩu.');
      return;
    }

    // 2. Kiểm tra định dạng email
    if (!EMAIL_REGEX.test(emailTrimmed)) {
      setErrorMsg('Email không đúng định dạng phổ biến.');
      return;
    }

    setIsLoading(true);

    try {
      const response = await fetch('/api/login', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          email: emailTrimmed,
          password: loginPassword,
        }),
      });

      const data = await response.json();

      if (!response.ok) {
        setErrorMsg(data.error || 'Đăng nhập không thành công.');
      } else {
        // Lưu token xác thực vào localStorage
        localStorage.setItem('auth_token', data.token);
        setCurrentUser(data.user);
        // Chuyển sang màn hình Hello theo yêu cầu
        setView('hello');
      }
    } catch (err) {
      setErrorMsg('Không thể kết nối đến máy chủ backend. Vui lòng kiểm tra lại server.');
    } finally {
      setIsLoading(false);
    }
  };

  // ==================== XỬ LÝ ĐĂNG XUẤT ====================
  const handleLogout = () => {
    localStorage.removeItem('auth_token');
    setCurrentUser(null);
    setLoginPassword('');
    switchView('login');
  };

  if (isInitializing) {
    return (
      <div className="app-container">
        <div className="card loading-card">
          <div className="spinner"></div>
          <p>Đang tải ứng dụng...</p>
        </div>
      </div>
    );
  }

  // ==================== MÀN HÌNH "HELLO" (SAU KHI ĐĂNG NHẬP) ====================
  if (view === 'hello' && currentUser) {
    return (
      <div className="app-container">
        <div className="card hello-card">
          <div className="hello-icon">👋</div>
          <h1 className="hello-title">Hello, {currentUser.email}!</h1>
          <p className="hello-subtitle">
            Chào mừng bạn đã đăng nhập thành công vào hệ thống.
          </p>

          <div className="user-details-box">
            <div className="detail-row">
              <span className="label">Mã tài khoản (User ID):</span>
              <span className="value">#{currentUser.id}</span>
            </div>
            <div className="detail-row">
              <span className="label">Email đã xác thực:</span>
              <span className="value">{currentUser.email}</span>
            </div>
            {currentUser.createdAt && (
              <div className="detail-row">
                <span className="label">Ngày tham gia:</span>
                <span className="value">
                  {new Date(currentUser.createdAt).toLocaleString('vi-VN')}
                </span>
              </div>
            )}
            <div className="detail-row">
              <span className="label">Trạng thái phiên:</span>
              <span className="badge-online">Đang hoạt động</span>
            </div>
          </div>

          <button className="btn btn-danger" onClick={handleLogout}>
            Đăng xuất
          </button>
        </div>
      </div>
    );
  }

  // ==================== MÀN HÌNH ĐĂNG NHẬP & ĐĂNG KÝ ====================
  return (
    <div className="app-container">
      <div className="card auth-card">
        {/* Tiêu đề & Chuyển đổi tab */}
        <div className="auth-header">
          <h2>Hệ Thống Xác Thực</h2>
          <div className="tab-buttons">
            <button
              type="button"
              className={`tab-btn ${view === 'login' ? 'active' : ''}`}
              onClick={() => switchView('login')}
            >
              Đăng nhập
            </button>
            <button
              type="button"
              className={`tab-btn ${view === 'register' ? 'active' : ''}`}
              onClick={() => switchView('register')}
            >
              Đăng ký
            </button>
          </div>
        </div>

        {/* Thông báo lỗi & thành công */}
        {errorMsg && <div className="alert alert-error">{errorMsg}</div>}
        {successMsg && <div className="alert alert-success">{successMsg}</div>}

        {/* ================= FORM ĐĂNG NHẬP ================= */}
        {view === 'login' && (
          <form onSubmit={handleLogin} className="auth-form" noValidate>
            <div className="form-group">
              <label htmlFor="login-email">Email</label>
              <input
                id="login-email"
                type="email"
                placeholder="vidu@domain.com"
                value={loginEmail}
                onChange={(e) => setLoginEmail(e.target.value)}
                autoComplete="email"
                required
              />
            </div>

            <div className="form-group">
              <label htmlFor="login-password">Mật khẩu</label>
              <div className="password-input-wrapper">
                <input
                  id="login-password"
                  type={showLoginPassword ? 'text' : 'password'}
                  placeholder="Nhập mật khẩu..."
                  value={loginPassword}
                  onChange={(e) => setLoginPassword(e.target.value)}
                  autoComplete="current-password"
                  required
                />
                <button
                  type="button"
                  className="btn-toggle-password"
                  onClick={() => setShowLoginPassword(!showLoginPassword)}
                  aria-label="Ẩn/hiện mật khẩu"
                >
                  {showLoginPassword ? 'Ẩn' : 'Hiện'}
                </button>
              </div>
            </div>

            <button type="submit" className="btn btn-primary" disabled={isLoading}>
              {isLoading ? 'Đang xử lý...' : 'Đăng nhập'}
            </button>

            <p className="auth-footer-text">
              Chưa có tài khoản?{' '}
              <button
                type="button"
                className="link-btn"
                onClick={() => switchView('register')}
              >
                Đăng ký ngay
              </button>
            </p>
          </form>
        )}

        {/* ================= FORM ĐĂNG KÝ ================= */}
        {view === 'register' && (
          <form onSubmit={handleRegister} className="auth-form" noValidate>
            <div className="form-group">
              <label htmlFor="reg-email">Email</label>
              <input
                id="reg-email"
                type="email"
                placeholder="vidu@domain.com"
                value={regEmail}
                onChange={(e) => setRegEmail(e.target.value)}
                autoComplete="email"
                required
              />
              <span className="field-hint">Định dạng email hợp lệ theo chuẩn phổ thông</span>
            </div>

            <div className="form-group">
              <label htmlFor="reg-password">Mật khẩu</label>
              <div className="password-input-wrapper">
                <input
                  id="reg-password"
                  type={showRegPassword ? 'text' : 'password'}
                  placeholder="8 - 32 ký tự..."
                  maxLength={PASSWORD_RULES.MAX_LENGTH}
                  value={regPassword}
                  onChange={(e) => setRegPassword(e.target.value)}
                  autoComplete="new-password"
                  required
                />
                <button
                  type="button"
                  className="btn-toggle-password"
                  onClick={() => setShowRegPassword(!showRegPassword)}
                  aria-label="Ẩn/hiện mật khẩu"
                >
                  {showRegPassword ? 'Ẩn' : 'Hiện'}
                </button>
              </div>

              {/* Danh sách tiêu chí mật khẩu trực quan */}
              <div className="password-requirements" aria-label="Yêu cầu mật khẩu">
                <div className={`requirement-item ${passwordCriteria.isLengthValid ? 'met' : ''}`}>
                  <span className="req-icon">{passwordCriteria.isLengthValid ? '✓' : '○'}</span>
                  <span>Từ {PASSWORD_RULES.MIN_LENGTH} đến {PASSWORD_RULES.MAX_LENGTH} ký tự</span>
                </div>
                <div className={`requirement-item ${passwordCriteria.hasUppercase ? 'met' : ''}`}>
                  <span className="req-icon">{passwordCriteria.hasUppercase ? '✓' : '○'}</span>
                  <span>Chứa ít nhất 1 chữ cái in hoa (A-Z)</span>
                </div>
                <div className={`requirement-item ${passwordCriteria.hasNumber ? 'met' : ''}`}>
                  <span className="req-icon">{passwordCriteria.hasNumber ? '✓' : '○'}</span>
                  <span>Chứa ít nhất 1 chữ số (0-9)</span>
                </div>
                <div className={`requirement-item ${passwordCriteria.hasSpecialChar ? 'met' : ''}`}>
                  <span className="req-icon">{passwordCriteria.hasSpecialChar ? '✓' : '○'}</span>
                  <span>Chứa ít nhất 1 ký tự đặc biệt (!@#$%^&*...)</span>
                </div>
              </div>
            </div>

            <div className="form-group">
              <label htmlFor="reg-confirm-password">Xác nhận mật khẩu</label>
              <div className="password-input-wrapper">
                <input
                  id="reg-confirm-password"
                  type={showRegPassword ? 'text' : 'password'}
                  placeholder="Nhập lại mật khẩu ở trên..."
                  maxLength={PASSWORD_RULES.MAX_LENGTH}
                  value={regConfirmPassword}
                  onChange={(e) => setRegConfirmPassword(e.target.value)}
                  autoComplete="new-password"
                  required
                />
              </div>
            </div>

            <button type="submit" className="btn btn-primary" disabled={isLoading}>
              {isLoading ? 'Đang đăng ký...' : 'Tạo tài khoản'}
            </button>

            <p className="auth-footer-text">
              Đã có tài khoản?{' '}
              <button
                type="button"
                className="link-btn"
                onClick={() => switchView('login')}
              >
                Đăng nhập
              </button>
            </p>
          </form>
        )}
      </div>
    </div>
  );
}
