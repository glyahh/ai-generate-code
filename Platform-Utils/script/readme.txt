使用指南:

1. 复制一份.env.example文件到 /script目录下
2. 完成sql配置(虽然不配置也回到local.yml文件中去找但是还是配一下比较好)
3. 运行执行窗口的对应操作文件就好啦


脚本源文件 ->
01_purge_deleted_apps_from_db.py
02_cleanup_temp_orphans.py
03_one_click_cleanup.py
04_cleanup_workflow_dirty_history.py
_common.py

执行窗口 ->
01_purge_deleted_apps_from_db_apply.py
01_purge_deleted_apps_from_db_preview.py
02_cleanup_temp_orphans_apply.py
02_cleanup_temp_orphans_preview.py
03_one_click_cleanup_apply.py
03_one_click_cleanup_preview.py
04_cleanup_workflow_dirty_history_apply.py
04_cleanup_workflow_dirty_history_preview.py

04 workflow历史脏数据清理（MySQL + Redis）:
1. 先预览:
   python script/04_cleanup_workflow_dirty_history.py
2. 指定 appId 预览:
   python script/04_cleanup_workflow_dirty_history.py --app-id 123456
3. 真正执行:
   python script/04_cleanup_workflow_dirty_history.py --apply
4. 执行后说明:
   - 会在 script/backup 生成 rollback SQL
   - 会清理命中 appId 的 Redis chat memory key
