/** Short notices at the bottom of the screen (errors from actions, confirmations). */
class Toasts {
  items = $state<{ id: number; text: string; error: boolean }[]>([]);
  private next = 0;

  show(text: string, error = false) {
    const id = this.next++;
    this.items.push({ id, text, error });
    setTimeout(() => (this.items = this.items.filter((t) => t.id !== id)), error ? 6000 : 3000);
  }

  dismiss(id: number) {
    this.items = this.items.filter((t) => t.id !== id);
  }

  error = (e: unknown) => {
    this.show(e instanceof Error ? e.message : String(e), true);
  };
}

export const toasts = new Toasts();
