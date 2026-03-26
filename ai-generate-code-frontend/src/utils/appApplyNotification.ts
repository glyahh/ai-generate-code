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

type IdLike = number | string | undefined | null;

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

function toId(value: IdLike): string | null {
  if (value == null) return null;
  const str = String(value).trim();
  return str || null;
}

/** 解析两个 ID 参数，任意一个无效则返回 null */
function parseIds(
  userId: IdLike,
  appId: IdLike,
): { userIdStr: string; appIdStr: string } | null {
  const userIdStr = toId(userId);
  const appIdStr = toId(appId);
  if (!userIdStr || !appIdStr) return null;
  return { userIdStr, appIdStr };
}

/** 从列表中找到匹配的通知下标 */
function findIndex(all: ApplyReviewNotification[], userIdStr: string, appIdStr: string): number {
  return all.findIndex((item) => item.userId === userIdStr && item.appId === appIdStr);
}

export function saveRejectNotification(userId: IdLike, appId: IdLike, reviewRemark: string) {
  const ids = parseIds(userId, appId);
  const remark = reviewRemark.trim();
  if (!ids || !remark) return;

  const { userIdStr, appIdStr } = ids;
  const all = loadAll();
  const next: ApplyReviewNotification = {
    userId: userIdStr,
    appId: appIdStr,
    reviewRemark: remark,
    status: 'rejected',
    unread: true,
    reviewedAt: new Date().toISOString(),
  };

  const idx = findIndex(all, userIdStr, appIdStr);
  if (idx >= 0) {
    all[idx] = next;
  } else {
    all.push(next);
  }
  saveAll(all);
}

export function getRejectNotification(userId: IdLike, appId: IdLike): ApplyReviewNotification | null {
  const ids = parseIds(userId, appId);
  if (!ids) return null;

  const { userIdStr, appIdStr } = ids;
  const found = loadAll().find((item) => item.userId === userIdStr && item.appId === appIdStr);
  return found?.status === 'rejected' ? found : null;
}

export function markRejectNotificationRead(userId: IdLike, appId: IdLike) {
  const ids = parseIds(userId, appId);
  if (!ids) return;

  const { userIdStr, appIdStr } = ids;
  const all = loadAll();
  const idx = findIndex(all, userIdStr, appIdStr);
  const cur = all[idx];
  if (!cur?.unread) return;

  all[idx] = { ...cur, unread: false };
  saveAll(all);
}

export function hasUnreadRejectNotification(userId: IdLike, appId: IdLike): boolean {
  const notification = getRejectNotification(userId, appId);
  return !!notification?.unread;
}

