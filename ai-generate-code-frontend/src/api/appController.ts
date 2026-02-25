/* eslint-disable */
// @ts-ignore
import request from '@/request';

import * as API from './types';

/** 此处后端没有提供注释 POST /app/add */
export function appAddUsingPost({
  body,
  options,
}: {
  body: API.AppAddRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseLong>('/app/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/admin/delete */
export function appAdminOpenApiDeleteUsingPost({
  body,
  options,
}: {
  body: API.DeleteRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseBoolean>('/app/admin/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /app/admin/get/vo */
export function appAdminGetVoUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.AppAdminGetVoUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseAppVO>('/app/admin/get/vo', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/admin/list/page/vo */
export function appAdminListPageVoUsingPost({
  body,
  options,
}: {
  body: API.AppQueryRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePageAppVO>('/app/admin/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/admin/update */
export function appAdminUpdateUsingPost({
  body,
  options,
}: {
  body: API.AppAdminUpdateRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseBoolean>('/app/admin/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/delete */
export function appOpenApiDeleteUsingPost({
  body,
  options,
}: {
  body: API.DeleteRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseBoolean>('/app/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/deploy */
export function appDeployUsingPost({
  body,
  options,
}: {
  body: API.AppDeployRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseString>('/app/deploy', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /app/get/vo */
export function appGetVoUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.AppGetVoUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseAppVO>('/app/get/vo', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/good/list/page/vo */
export function appGoodListPageVoUsingPost({
  body,
  options,
}: {
  body: API.AppQueryRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePageAppVO>('/app/good/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/my/list/page/vo */
export function appMyListPageVoUsingPost({
  body,
  options,
}: {
  body: API.AppQueryRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePageAppVO>('/app/my/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /app/update */
export function appUpdateUsingPost({
  body,
  options,
}: {
  body: API.AppUpdateRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseBoolean>('/app/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}
