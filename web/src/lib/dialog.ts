/** Native modal behavior: focus containment, inert background, and focus restoration on close. */
export function modal(dialog: HTMLDialogElement) {
  const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  dialog.showModal();
  return () => {
    dialog.close();
    // Conditional dialogs can unmount during the browser's cancel event. Restore focus
    // after that event and Svelte's DOM update have finished, including nested dialogs.
    queueMicrotask(() => {
      if (trigger?.isConnected) trigger.focus({ preventScroll: true });
    });
  };
}
