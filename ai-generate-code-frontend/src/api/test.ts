/* eslint-disable */
// @ts-ignore
import request from '@/request';

import * as API from './types';

/** 此处后端没有提供注释 GET /test/ */
export function testUsingGet({
  options,
}: {
  options?: { [key: string]: unknown };
}) {
  return request<API.BaseResponseObject>('/test/', {
    method: 'GET',
    ...(options || {}),
  });
}
