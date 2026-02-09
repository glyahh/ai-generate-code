/* eslint-disable */
// @ts-ignore

export type BaseResponseObject = {
  code?: number;
  data?: Record<string, unknown>;
  message?: string;
};

export type TestUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseObject;
};
