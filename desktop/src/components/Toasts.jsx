import { AlertTriangle, CheckCircle2, MessageSquare, XCircle } from 'lucide-react';

const ICON = { ok: CheckCircle2, warn: AlertTriangle, error: XCircle, msg: MessageSquare };

export default function Toasts({ items }) {
  return (
    <div className="toasts" aria-live="polite">
      {items.map((t) => {
        const I = ICON[t.level] ?? CheckCircle2;
        return <div key={t.id} className={`toast ${t.level}`}><I size={17} />{t.msg}</div>;
      })}
    </div>
  );
}
