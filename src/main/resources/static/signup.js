const state = {
  filials: [],
  courses: [],
  classes: [],
  selectedClass: null,
  contactUrl: 'https://max.ru/id246516134480_2_bot'
};

const $ = (id) => document.getElementById(id);

const filialSelect = $('filialSelect');
const courseSelect = $('courseSelect');
const groupsList = $('groupsList');
const groupsCount = $('groupsCount');
const selectedGroup = $('selectedGroup');
const selectedClassId = $('selectedClassId');
const submitButton = $('submitButton');
const formMessage = $('formMessage');
const form = $('signupForm');
const payLater = $('payLater');
const successDialog = $('successDialog');
const payLink = $('payLink');
const contactLink = $('contactLink');

function option(value, text) {
  const item = document.createElement('option');
  item.value = value;
  item.textContent = text;
  return item;
}

function renderSelects() {
  filialSelect.innerHTML = '';
  filialSelect.append(option('', 'Выберите детский сад'));
  state.filials.forEach((filial) => filialSelect.append(option(filial.id, filial.name)));

  courseSelect.innerHTML = '';
  courseSelect.append(option('', 'Все направления'));
  state.courses.forEach((course) => courseSelect.append(option(course.id, course.name)));
}

function filteredClasses() {
  const filialId = Number(filialSelect.value || 0);
  const courseId = Number(courseSelect.value || 0);
  return state.classes.filter((group) => {
    if (filialId && group.filialId !== filialId) return false;
    if (courseId && group.courseId !== courseId) return false;
    return true;
  });
}

function renderGroups() {
  const groups = filteredClasses();
  groupsCount.textContent = groups.length;
  groupsList.innerHTML = '';
  if (!groups.length) {
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    empty.textContent = 'Для выбранных параметров нет открытых групп.';
    groupsList.append(empty);
    clearSelectedGroup();
    return;
  }

  groups.forEach((group) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'group-button';
    button.textContent = group.name;
    if (state.selectedClass && state.selectedClass.id === group.id) {
      button.classList.add('is-selected');
    }
    button.addEventListener('click', () => {
      state.selectedClass = group;
      selectedClassId.value = group.id;
      selectedGroup.textContent = `Выбрана группа: ${group.name}`;
      submitButton.disabled = false;
      submitButton.textContent = payLater.checked ? 'Отправить заявку' : 'Записаться и перейти к оплате';
      renderGroups();
    });
    groupsList.append(button);
  });

  if (state.selectedClass && !groups.some((group) => group.id === state.selectedClass.id)) {
    clearSelectedGroup();
  }
}

function clearSelectedGroup() {
  state.selectedClass = null;
  selectedClassId.value = '';
  selectedGroup.textContent = 'Выберите группу выше';
  submitButton.disabled = true;
  submitButton.textContent = 'Выберите группу';
}

function updateSubmitText() {
  if (!state.selectedClass) return;
  submitButton.textContent = payLater.checked ? 'Отправить заявку' : 'Записаться и перейти к оплате';
}

function formatPhone(value) {
  const digits = value.replace(/\D/g, '').replace(/^8/, '7').slice(0, 11);
  if (!digits) return '';
  const local = digits.startsWith('7') ? digits.slice(1) : digits;
  const parts = [
    local.slice(0, 3),
    local.slice(3, 6),
    local.slice(6, 8),
    local.slice(8, 10)
  ].filter(Boolean);
  if (!parts.length) return '+7 ';
  let result = '+7';
  if (parts[0]) result += ` (${parts[0]}`;
  if (parts[0] && parts[0].length === 3) result += ')';
  if (parts[1]) result += ` ${parts[1]}`;
  if (parts[2]) result += `-${parts[2]}`;
  if (parts[3]) result += `-${parts[3]}`;
  return result;
}

async function loadOptions() {
  try {
    const response = await fetch('/api/signup/options');
    if (!response.ok) throw new Error('Не удалось загрузить расписание.');
    const data = await response.json();
    state.filials = data.filials || [];
    state.courses = data.courses || [];
    state.classes = data.classes || [];
    state.contactUrl = data.contactUrl || state.contactUrl;
    $('contactTop').href = state.contactUrl;
    contactLink.href = state.contactUrl;
    renderSelects();
    renderGroups();
  } catch (error) {
    groupsList.innerHTML = `<div class="empty-state">${error.message}</div>`;
    groupsCount.textContent = '0';
  }
}

function validateForm() {
  if (!state.selectedClass) return 'Выберите группу.';
  if (!$('childName').value.trim()) return 'Укажите ФИО ребенка.';
  if (!$('parentName').value.trim()) return 'Укажите ФИО родителя.';
  if ($('phone').value.replace(/\D/g, '').length < 10) return 'Укажите корректный телефон.';
  if (!$('email').value.includes('@')) return 'Укажите корректный email.';
  if (!$('personalConsent').checked) return 'Подтвердите согласие на обработку персональных данных.';
  return '';
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  formMessage.textContent = '';
  const error = validateForm();
  if (error) {
    formMessage.textContent = error;
    return;
  }

  submitButton.disabled = true;
  submitButton.textContent = 'Отправляем...';
  try {
    const response = await fetch('/api/signup', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        childName: $('childName').value.trim(),
        parentName: $('parentName').value.trim(),
        phone: $('phone').value,
        email: $('email').value.trim(),
        filialId: Number(filialSelect.value),
        courseId: Number(courseSelect.value || 0),
        classId: Number(selectedClassId.value),
        payLater: payLater.checked,
        personalConsent: $('personalConsent').checked,
        marketingConsent: $('marketingConsent').checked
      })
    });
    const data = await response.json();
    if (!response.ok || !data.ok) throw new Error(data.message || 'Не удалось отправить заявку.');

    if (data.payLink && !payLater.checked) {
      payLink.href = data.payLink;
      payLink.hidden = false;
    } else {
      payLink.hidden = true;
    }
    successDialog.showModal();
    form.reset();
    filialSelect.value = '';
    courseSelect.value = '';
    clearSelectedGroup();
    renderGroups();
  } catch (error) {
    formMessage.textContent = error.message;
  } finally {
    submitButton.disabled = !state.selectedClass;
    updateSubmitText();
  }
});

filialSelect.addEventListener('change', renderGroups);
courseSelect.addEventListener('change', renderGroups);
payLater.addEventListener('change', updateSubmitText);
$('phone').addEventListener('input', (event) => {
  event.target.value = formatPhone(event.target.value);
});
$('closeDialog').addEventListener('click', () => successDialog.close());

loadOptions();
