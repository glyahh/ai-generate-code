const STORAGE_KEY = 'appApplyReviewNotifications';

export type ApplyReviewNotificationStatus = 'rejected' | 'approved';

export type ApplyReviewNotification = {
  userId: string;
  appId: string;
  reviewRemark: string;
  status: ApplyReviewNotificationStatus;
  unread: boolean;
  reviewedAt: string;
};

function isBrowser() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined';
}

function loadAll(): ApplyReviewNotification[] {
  if (!isBrowser()) return [];
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed.filter(
        (item) =>
          item &&
          typeof item.userId === 'string' &&
          typeof item.appId === 'string' &&
          typeof item.reviewRemark === 'string',
      );
    }
  } catch {
    // ignore
  }
  return [];
}

function saveAll(list: ApplyReviewNotification[]) {
  if (!isBrowser()) return;
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
  } catch {
    // ignore
  }
}

function toId(value: number | string | undefined | null): string | null {
  if (value === undefined || value === null) return null;
  const str = String(value).trim();
  return str ? str : null;
}

export function saveRejectNotification(
  userId: number | string | undefined | null,
  appId: number | string | undefined | null,
  reviewRemark: string,
) {
  const userIdStr = toId(userId);
  const appIdStr = toId(appId);
  const remark = reviewRemark.trim();

  if (!userIdStr || !appIdStr || !remark) return;

  const all = loadAll();
  const now = new Date().toISOString();
  const idx = all.findIndex((item) => item.userId === userIdStr && item.appId === appIdStr);

  const next: ApplyReviewNotification = {
    userId: userIdStr,
    appId: appIdStr,
    reviewRemark: remark,
    status: 'rejected',
    unread: true,
    reviewedAt: now,
  };

  if (idx >= 0) {
    all[idx] = next;
  } else {
    all.push(next);
  }
  saveAll(all);
}

export function getRejectNotification(
  userId: number | string | undefined | null,
  appId: number | string | undefined | null,
): ApplyReviewNotification | null {
  const userIdStr = toId(userId);
  const appIdStr = toId(appId);
  if (!userIdStr || !appIdStr) return null;

  const all = loadAll();
  const found = all.find((item) => item.userId === userIdStr && item.appId === appIdStr);
  if (!found || found.status !== 'rejected') return null;
  return found;
}

export function markRejectNotificationRead(
  userId: number | string | undefined | null,
  appId: number | string | undefined | null,
) {
  const userIdStr = toId(userId);
  const appIdStr = toId(appId);
  if (!userIdStr || !appIdStr) return;

  const all = loadAll();
  const idx = all.findIndex((item) => item.userId === userIdStr && item.appId === appIdStr);
  if (idx < 0) return;

  if (!all[idx].unread) return;
  all[idx] = {
    ...all[idx],
    unread: false,
  };
  saveAll(all);
}

export function hasUnreadRejectNotification(
  userId: number | string | undefined | null,
  appId: number | string | undefined | null,
): boolean {
  const notification = getRejectNotification(userId, appId);
  return !!notification && notification.unread;
}

