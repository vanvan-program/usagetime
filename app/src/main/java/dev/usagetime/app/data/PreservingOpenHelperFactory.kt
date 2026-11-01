package dev.usagetime.app.data

import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/** Suppress the platform callback's default delete-on-corruption path. Never recreate silently. */
class PreservingOpenHelperFactory:SupportSQLiteOpenHelper.Factory{
    override fun create(configuration:SupportSQLiteOpenHelper.Configuration):SupportSQLiteOpenHelper{
        val original=configuration.callback
        val safe=object:SupportSQLiteOpenHelper.Callback(original.version){
            override fun onConfigure(db:SupportSQLiteDatabase)=original.onConfigure(db)
            override fun onCreate(db:SupportSQLiteDatabase)=original.onCreate(db)
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=original.onUpgrade(db,oldVersion,newVersion)
            override fun onDowngrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=original.onDowngrade(db,oldVersion,newVersion)
            override fun onOpen(db:SupportSQLiteDatabase)=original.onOpen(db)
            override fun onCorruption(db:SupportSQLiteDatabase){throw SQLiteDatabaseCorruptException("数据库异常，原文件已保留，请从本机安全副本恢复")}
        }
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context).name(configuration.name).callback(safe).noBackupDirectory(configuration.useNoBackupDirectory).allowDataLossOnRecovery(false).build())
    }
}
