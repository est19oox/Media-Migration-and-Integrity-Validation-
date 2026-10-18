// Preserve the team's page controllers during the React migration. Pages use
// full-document navigation, so listeners and timers are disposed on navigation.
// React mounts the page once; controllers own their existing dynamic subtrees.
const loaded = new Map();
export async function loadControllers(paths) {
  try {
    for (const path of paths) {
      if (!loaded.has(path))
        loaded.set(
          path,
          new Promise((resolve, reject) => {
            const script = document.createElement('script');
            script.src = path;
            script.onload = resolve;
            script.onerror = () => reject(new Error(`Cannot load ${path}`));
            document.body.append(script);
          }),
        );
      await loaded.get(path);
    }
    document.documentElement.dataset.controllersReady = 'true';
  } catch (error) {
    console.error(error);
    const alert = document.createElement('p');
    alert.setAttribute('role', 'alert');
    alert.textContent = 'This page could not finish loading. Please refresh and try again.';
    document.body.prepend(alert);
  }
}
