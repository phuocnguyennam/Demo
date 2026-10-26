import React, { useState, useEffect, useCallback } from 'react';

const HISTORY_PAGE_SIZE = 50;
const FIELD_LABELS = { name: 'Tên', phone_number: 'Số điện thoại', role: 'Vai trò' };

const authHeaders = () => ({
  Authorization: `Bearer ${localStorage.getItem('auth_token')}`,
  'Content-Type': 'application/json',
});

const formatValue = (v) => (v === null || v === undefined || v === '' ? '(trống)' : String(v));

export default function Dashboard() {
  const [me, setMe] = useState(null); // hồ sơ của người đăng nhập
  const [profile, setProfile] = useState(null); // hồ sơ đang xem/chỉnh sửa
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [role, setRole] = useState('user');
  const [msg, setMsg] = useState({ type: '', text: '' });
  const [saving, setSaving] = useState(false);

  // Admin: tìm người dùng
  const [search, setSearch] = useState('');
  const [searchResults, setSearchResults] = useState([]);

  // Lịch sử
  const [history, setHistory] = useState([]);
  const [historyError, setHistoryError] = useState('');
  const [historyLoading, setHistoryLoading] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [actorFilter, setActorFilter] = useState(null); // { id, name }
  const [targetFilter, setTargetFilter] = useState(null);
  const [filterSearch, setFilterSearch] = useState({ actor: '', target: '' });
  const [filterResults, setFilterResults] = useState({ actor: [], target: [] });

  const isAdmin = me && me.role === 'admin';

  const applyProfile = (p) => {
    setProfile(p);
    setName(p.name || '');
    setPhone(p.phoneNumber || '');
    setRole(p.role);
  };

  const loadProfile = useCallback(async (userId) => {
    const url = userId ? `/api/profile?userId=${userId}` : '/api/profile';
    const res = await fetch(url, { headers: authHeaders() });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'Không tải được hồ sơ.');
    return data.profile;
  }, []);

  // append=false: tải lại từ đầu; append=true: tải thêm các bản ghi cũ hơn.
  const loadHistory = useCallback(
    async (append = false, offset = 0) => {
      const params = new URLSearchParams();
      if (actorFilter) params.set('actorId', actorFilter.id);
      if (targetFilter) params.set('targetId', targetFilter.id);
      params.set('limit', HISTORY_PAGE_SIZE);
      params.set('offset', append ? offset : 0);
      setHistoryLoading(true);
      setHistoryError('');
      try {
        const res = await fetch(`/api/history?${params.toString()}`, { headers: authHeaders() });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
          throw new Error(data.error || 'Không tải được lịch sử chỉnh sửa.');
        }
        setHistory((prev) => (append ? [...prev, ...data.history] : data.history));
        setHasMore(Boolean(data.hasMore));
      } catch (err) {
        setHistoryError(
          err instanceof TypeError ? 'Không thể kết nối tới máy chủ để tải lịch sử.' : err.message
        );
      } finally {
        setHistoryLoading(false);
      }
    },
    [actorFilter, targetFilter]
  );

  useEffect(() => {
    loadProfile()
      .then((p) => {
        setMe(p);
        applyProfile(p);
      })
      .catch((e) => setMsg({ type: 'error', text: e.message }));
  }, [loadProfile]);

  useEffect(() => {
    if (me) loadHistory();
  }, [me, loadHistory]);

  const searchUsers = async (term, setter) => {
    const res = await fetch(`/api/users?name=${encodeURIComponent(term)}`, { headers: authHeaders() });
    const data = await res.json();
    if (res.ok) setter(data.users);
  };

  const selectUser = async (u) => {
    setMsg({ type: '', text: '' });
    try {
      applyProfile(await loadProfile(u.id));
      setSearchResults([]);
      setSearch(u.name);
    } catch (e) {
      setMsg({ type: 'error', text: e.message });
    }
  };

  const handleSave = async (e) => {
    e.preventDefault();
    setMsg({ type: '', text: '' });
    if (!name.trim()) {
      setMsg({ type: 'error', text: 'Tên không được để trống.' });
      return;
    }
    setSaving(true);
    try {
      const body = { userId: profile.id, name, phoneNumber: phone };
      if (isAdmin) body.role = role;
      const res = await fetch('/api/profile', {
        method: 'PUT',
        headers: authHeaders(),
        body: JSON.stringify(body),
      });
      const data = await res.json();
      if (!res.ok) {
        setMsg({ type: 'error', text: data.error || 'Cập nhật thất bại.' });
      } else {
        applyProfile(data.profile);
        if (data.profile.id === me.id) setMe(data.profile);
        setMsg({ type: 'success', text: data.message });
        loadHistory();
      }
    } catch (err) {
      setMsg({ type: 'error', text: 'Không thể kết nối tới máy chủ.' });
    } finally {
      setSaving(false);
    }
  };

  const renderFilter = (kind, label, value, setValue) => (
    <div className="form-group filter-group">
      <label>{label}</label>
      {value ? (
        <div className="filter-chip">
          {value.name}
          <button type="button" className="link-btn" onClick={() => setValue(null)}>
            Bỏ lọc
          </button>
        </div>
      ) : (
        <>
          <input
            type="text"
            placeholder="Nhập tên để tìm..."
            value={filterSearch[kind]}
            onChange={(e) => {
              const v = e.target.value;
              setFilterSearch({ ...filterSearch, [kind]: v });
              searchUsers(v, (r) => setFilterResults((prev) => ({ ...prev, [kind]: r })));
            }}
          />
          {filterResults[kind].length > 0 && filterSearch[kind] && (
            <ul className="user-suggestions">
              {filterResults[kind].map((u) => (
                <li key={u.id}>
                  <button
                    type="button"
                    onClick={() => {
                      setValue(u);
                      setFilterSearch({ ...filterSearch, [kind]: '' });
                      setFilterResults((prev) => ({ ...prev, [kind]: [] }));
                    }}
                  >
                    {u.name} ({u.email})
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );

  if (!profile) {
    return msg.text ? <div className="alert alert-error">{msg.text}</div> : null;
  }

  return (
    <div className="dashboard">
      <section className="dashboard-section">
        <h3>Thông tin người dùng</h3>

        {isAdmin && (
          <div className="form-group">
            <label htmlFor="admin-search">Tìm người dùng theo tên (admin)</label>
            <input
              id="admin-search"
              type="text"
              placeholder="Nhập tên người dùng..."
              value={search}
              onChange={(e) => {
                setSearch(e.target.value);
                searchUsers(e.target.value, setSearchResults);
              }}
              onFocus={() => searchUsers(search, setSearchResults)}
            />
            {searchResults.length > 0 && (
              <ul className="user-suggestions">
                {searchResults.map((u) => (
                  <li key={u.id}>
                    <button type="button" onClick={() => selectUser(u)}>
                      {u.name} ({u.email})
                    </button>
                  </li>
                ))}
              </ul>
            )}
            {profile.id !== me.id && (
              <button
                type="button"
                className="link-btn"
                onClick={() => {
                  applyProfile(me);
                  setSearch('');
                  setSearchResults([]);
                }}
              >
                Quay lại hồ sơ của tôi
              </button>
            )}
          </div>
        )}

        {msg.text && (
          <div className={`alert ${msg.type === 'error' ? 'alert-error' : 'alert-success'}`}>{msg.text}</div>
        )}

        <form onSubmit={handleSave} className="auth-form" noValidate>
          <div className="form-group">
            <label>Email (ID #{profile.id})</label>
            <input type="text" value={profile.email} disabled />
          </div>
          <div className="form-group">
            <label htmlFor="profile-name">Tên</label>
            <input id="profile-name" type="text" maxLength={255} value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="form-group">
            <label htmlFor="profile-phone">Số điện thoại</label>
            <input id="profile-phone" type="text" maxLength={30} value={phone} onChange={(e) => setPhone(e.target.value)} />
          </div>
          <div className="form-group">
            <label htmlFor="profile-role">Vai trò</label>
            <select
              id="profile-role"
              value={role}
              onChange={(e) => setRole(e.target.value)}
              disabled={!isAdmin || profile.id === me.id}
            >
              <option value="user">user</option>
              <option value="admin">admin</option>
            </select>
          </div>
          <button type="submit" className="btn btn-primary" disabled={saving}>
            {saving ? 'Đang lưu...' : 'Lưu thay đổi'}
          </button>
        </form>
      </section>

      <section className="dashboard-section">
        <h3>Lịch sử chỉnh sửa</h3>
        {isAdmin && (
          <div className="history-filters">
            {renderFilter('actor', 'Lọc theo người thực hiện', actorFilter, setActorFilter)}
            {renderFilter('target', 'Lọc theo người được chỉnh sửa', targetFilter, setTargetFilter)}
          </div>
        )}
        {historyError && (
          <div className="alert alert-error">
            {historyError}{' '}
            <button type="button" className="link-btn" onClick={() => loadHistory()}>
              Thử lại
            </button>
          </div>
        )}
        {!historyError && history.length === 0 ? (
          <p className="hello-subtitle">
            {historyLoading ? 'Đang tải lịch sử...' : 'Chưa có lịch sử chỉnh sửa.'}
          </p>
        ) : (
          <ul className="history-list">
            {history.map((h) => (
              <li key={h.id} className="history-item">
                <div className="history-meta">
                  <strong>{h.actorName || `#${h.actorId}`}</strong> đã chỉnh sửa{' '}
                  <strong>{h.targetName || `#${h.targetId}`}</strong>
                  <span className="history-time">{new Date(h.editedAt).toLocaleString('vi-VN')}</span>
                </div>
                <ul className="history-changes">
                  {h.changedFields &&
                    Object.keys(h.changedFields).map((f) => (
                      <li key={f}>
                        {FIELD_LABELS[f] || f}: {formatValue(h.changedFields[f].before)} →{' '}
                        {formatValue(h.changedFields[f].after)}
                      </li>
                    ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
        {hasMore && (
          <button
            type="button"
            className="btn btn-primary"
            disabled={historyLoading}
            onClick={() => loadHistory(true, history.length)}
          >
            {historyLoading ? 'Đang tải...' : 'Tải thêm'}
          </button>
        )}
      </section>
    </div>
  );
}
