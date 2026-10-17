function openUserManagement(username) {
  const dialog = document.getElementById('userManagementDialog');
  const select = document.getElementById('selectedUser');

  select.value = username;
  dialog.showModal();
}
