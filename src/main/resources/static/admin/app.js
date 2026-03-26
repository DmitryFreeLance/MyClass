const tokenInput = document.getElementById('token');
const saveTokenButton = document.getElementById('saveToken');
const startDialogButton = document.getElementById('startDialog');
const refreshButton = document.getElementById('refreshDialogs');
const dialogsContainer = document.getElementById('dialogs');
const statusEl = document.getElementById('startStatus');

const savedToken = localStorage.getItem('adminToken');
if (savedToken) {
  tokenInput.value = savedToken;
}

saveTokenButton.addEventListener('click', () => {
  localStorage.setItem('adminToken', tokenInput.value.trim());
  statusEl.textContent = 'Токен сохранен.';
});

refreshButton.addEventListener('click', () => {
  loadDialogs();
});

startDialogButton.addEventListener('click', async () => {
  statusEl.textContent = '';
  const userId = Number(document.getElementById('userId').value);
  const message = document.getElementById('message').value;
  if (!userId) {
    statusEl.textContent = 'Введите user_id.';
    return;
  }

  try {
    await apiRequest('/admin/api/dialogs/start', {
      userId,
      message
    });
    statusEl.textContent = 'Диалог запущен.';
    await loadDialogs();
  } catch (error) {
    statusEl.textContent = error.message;
  }
});

async function loadDialogs() {
  dialogsContainer.innerHTML = 'Загрузка...';
  try {
    const dialogs = await apiRequest('/admin/api/dialogs');
    if (!Array.isArray(dialogs) || dialogs.length === 0) {
      dialogsContainer.innerHTML = '<p class="muted">Активных диалогов нет.</p>';
      return;
    }

    dialogsContainer.innerHTML = '';
    dialogs.forEach(dialog => {
      const item = document.createElement('div');
      item.className = 'dialog-item';
      const name = [dialog.firstName, dialog.lastName].filter(Boolean).join(' ');
      item.innerHTML = `
        <div>
          <div class="dialog-title">User ${dialog.userId}</div>
          <div class="muted">${name || 'без имени'} ${dialog.username ? '(' + dialog.username + ')' : ''}</div>
        </div>
        <button class="button danger" data-id="${dialog.id}">Закрыть</button>
      `;
      item.querySelector('button').addEventListener('click', async () => {
        try {
          await apiRequest('/admin/api/dialogs/close', { dialogId: dialog.id });
          await loadDialogs();
        } catch (error) {
          alert(error.message);
        }
      });
      dialogsContainer.appendChild(item);
    });
  } catch (error) {
    dialogsContainer.innerHTML = `<p class="muted">${error.message}</p>`;
  }
}

async function apiRequest(path, body) {
  const token = tokenInput.value.trim();
  if (!token) {
    throw new Error('Введите admin token.');
  }

  const options = {
    headers: {
      'Content-Type': 'application/json',
      'X-Admin-Token': token
    }
  };

  if (body) {
    options.method = 'POST';
    options.body = JSON.stringify(body);
  }

  const response = await fetch(path, options);
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || 'Ошибка запроса');
  }
  const contentType = response.headers.get('content-type') || '';
  return contentType.includes('application/json') ? response.json() : response.text();
}

loadDialogs();
