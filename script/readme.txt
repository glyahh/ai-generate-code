使用指南:

1. 复制一份.env.example文件到 /script目录下
2. 完成sql配置(虽然不配置也回到local.yml文件中去找但是还是配一下比较好)
3. 运行执行窗口的对应操作文件就好啦


脚本源文件 ->
01_purge_deleted_apps_from_db.py
02_cleanup_temp_orphans.py
03_one_click_cleanup.py
_common.py

执行窗口 ->
01_purge_deleted_apps_from_db_apply.py
01_purge_deleted_apps_from_db_preview.py
02_cleanup_temp_orphans_apply.py
02_cleanup_temp_orphans_preview.py
03_one_click_cleanup_apply.py
03_one_click_cleanup_preview.py