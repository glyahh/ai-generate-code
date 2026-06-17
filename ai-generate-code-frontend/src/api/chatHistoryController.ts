/* eslint-disable */
// @ts-ignore
import request from '@/request';

import * as API from './types';

/** 此处后端没有提供注释 GET /chatHistory/app/${param0} */
export function chatHistoryAppAppIdUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.ChatHistoryAppAppIdUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  const { appId: param0, ...queryParams } = params;

  return request<API.BaseResponseAppChatHistoryPage>(
    `/chatHistory/app/${param0}`,
    {
      method: 'GET',
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /chatHistory/export/${param0} */
export function chatHistoryOpenApiExportAppIdUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.ChatHistoryOpenApiExportAppIdUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  const { appId: param0, ...queryParams } = params;

  return request<API.BaseResponseString>(
    `/chatHistory/export/${param0}`,
    {
      method: 'GET',
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 POST /chatHistory/myChatHistory */
export function chatHistoryMyUsingPost({
  body,
  options,
}: {
  body: API.ChatHistoryQueryRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePageUserChatHistoryItemVO>('/chatHistory/myChatHistory', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /chatHistory/deleteByAppId/${param0} */
export function chatHistoryDeleteByAppIdUsingPost({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.ChatHistoryDeleteByAppIdUsingPostParams;
  options?: { [key: string]: unknown };
}) {
  const { appId: param0, ...queryParams } = params;

  return request<API.BaseResponseBoolean>(
    `/chatHistory/deleteByAppId/${param0}`,
    {
      method: 'POST',
      params: { ...queryParams },
      ...(options || {}),
    }
  );
}

/** 此处后端没有提供注释 GET /chatHistory/search */
export function chatHistorySearchUsingGet({
  params,
  options,
}: {
  // 叠加生成的Param类型 (非body参数openapi默认没有生成对象)
  params: API.ChatHistorySearchUsingGetParams;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePageChatHistoryAppVO>('/chatHistory/search', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 POST /chatHistory/admin/page/vo */
export function chatHistoryAdminUsingPost({
  body,
  options,
}: {
  body: API.ChatHistoryQueryRequest;
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponsePageChatHistoryAppVO>('/chatHistory/admin/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}
