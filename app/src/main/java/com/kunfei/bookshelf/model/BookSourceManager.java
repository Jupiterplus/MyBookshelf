package com.kunfei.bookshelf.model;

import android.database.Cursor;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.kunfei.bookshelf.MApplication;
import com.kunfei.bookshelf.base.BaseModelImpl;
import com.kunfei.bookshelf.bean.BookSourceBean;
import com.kunfei.bookshelf.dao.BookSourceBeanDao;
import com.kunfei.bookshelf.dao.DbHelper;
import com.kunfei.bookshelf.model.analyzeRule.AnalyzeHeaders;
import com.kunfei.bookshelf.model.impl.IHttpGetApi;
import com.kunfei.bookshelf.utils.NetworkUtil;
import com.kunfei.bookshelf.utils.RxUtils;
import com.kunfei.bookshelf.utils.StringUtils;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.Nullable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.SingleOnSubscribe;

/**
 * Created by GKF on 2017/12/15.
 * 所有书源
 */

public class BookSourceManager {

    public static BookSourceManager getInstance() {
        return new BookSourceManager();
    }

    public static List<BookSourceBean> getSelectedBookSource() {
        return DbHelper.getDaoSession().getBookSourceBeanDao().queryBuilder()
                    .where(BookSourceBeanDao.Properties.Enable.eq(true))
                    .orderRaw(BookSourceBeanDao.Properties.Weight.columnName + " DESC")
                    .orderAsc(BookSourceBeanDao.Properties.SerialNumber)
                    .list();
    }

    public static List<BookSourceBean> getAllBookSource() {
        return DbHelper.getDaoSession().getBookSourceBeanDao().queryBuilder()
                    .orderRaw(getBookSourceSort())
                    .orderAsc(BookSourceBeanDao.Properties.SerialNumber)
                    .list();
    }

    public static List<BookSourceBean> getSelectedBookSourceBySerialNumber() {
        return DbHelper.getDaoSession().getBookSourceBeanDao().queryBuilder()
                .where(BookSourceBeanDao.Properties.Enable.eq(true))
                .orderAsc(BookSourceBeanDao.Properties.SerialNumber)
                .list();
    }

    public static List<BookSourceBean> getAllBookSourceBySerialNumber() {
        return DbHelper.getDaoSession().getBookSourceBeanDao().queryBuilder()
                .orderAsc(BookSourceBeanDao.Properties.SerialNumber)
                .list();
    }

    @Nullable
    public static BookSourceBean getBookSourceByUrl(String url) {
        return DbHelper.getDaoSession().getBookSourceBeanDao().load(url);
    }

    public static void removeBookSource(BookSourceBean sourceBean) {
        if (sourceBean == null) return;
        DbHelper.getDaoSession().getBookSourceBeanDao().delete(sourceBean);
    }

    public static String getBookSourceSort() {
        switch (MApplication.getConfigPreferences().getInt("SourceSort", 0)) {
            case 1:
                return BookSourceBeanDao.Properties.Weight.columnName + " DESC";
            case 2:
                return BookSourceBeanDao.Properties.BookSourceName.columnName + " COLLATE LOCALIZED ASC";
            default:
                return BookSourceBeanDao.Properties.SerialNumber.columnName + " ASC";
        }
    }

    public static void addBookSource(List<BookSourceBean> bookSourceBeans) {
        for (BookSourceBean bookSourceBean : bookSourceBeans) {
            addBookSource(bookSourceBean);
        }
    }

    public static void addBookSource(BookSourceBean bookSourceBean) {
        if (TextUtils.isEmpty(bookSourceBean.getBookSourceName()) || TextUtils.isEmpty(bookSourceBean.getBookSourceUrl()))
            return;
        if (bookSourceBean.getBookSourceUrl().endsWith("/")) {
            bookSourceBean.setBookSourceUrl(bookSourceBean.getBookSourceUrl().replaceAll("/+$", ""));
        }
        BookSourceBean temp = DbHelper.getDaoSession().getBookSourceBeanDao().queryBuilder()
                .where(BookSourceBeanDao.Properties.BookSourceUrl.eq(bookSourceBean.getBookSourceUrl())).unique();
        if (temp != null) {
            bookSourceBean.setSerialNumber(temp.getSerialNumber());
            bookSourceBean.setEnable(temp.getEnable());
        } else {
            bookSourceBean.setEnable(true);
        }
        if (bookSourceBean.getSerialNumber() < 0) {
            bookSourceBean.setSerialNumber((int) (DbHelper.getDaoSession().getBookSourceBeanDao().queryBuilder().count() + 1));
        }
        DbHelper.getDaoSession().getBookSourceBeanDao().insertOrReplace(bookSourceBean);
    }

    public static void saveBookSource(BookSourceBean bookSourceBean) {
        if (bookSourceBean != null) {
            DbHelper.getDaoSession().getBookSourceBeanDao().insertOrReplace(bookSourceBean);
        }
    }

    public static Single<Boolean> toTop(BookSourceBean sourceBean) {
        return Single.create((SingleOnSubscribe<Boolean>) e -> {
            List<BookSourceBean> beanList = getAllBookSourceBySerialNumber();
            for (int i = 0; i < beanList.size(); i++) {
                beanList.get(i).setSerialNumber(i + 1);
            }
            sourceBean.setSerialNumber(0);
            DbHelper.getDaoSession().getBookSourceBeanDao().insertOrReplaceInTx(beanList);
            DbHelper.getDaoSession().getBookSourceBeanDao().insertOrReplace(sourceBean);
            e.onSuccess(true);
        }).compose(RxUtils::toSimpleSingle);
    }

    public static List<String> getGroupList() {
        List<String> groupList = new ArrayList<>();
        String sql = "SELECT DISTINCT " + BookSourceBeanDao.Properties.BookSourceGroup.columnName + " FROM " + BookSourceBeanDao.TABLENAME;
        Cursor cursor = DbHelper.getDaoSession().getDatabase().rawQuery(sql, null);
        if (!cursor.moveToFirst()) return groupList;
        do {
            String group = cursor.getString(0);
            if (TextUtils.isEmpty(group) || TextUtils.isEmpty(group.trim())) continue;
            for (String item : group.split("\\s*[,;，；]\\s*")) {
                if (TextUtils.isEmpty(item) || groupList.contains(item)) continue;
                groupList.add(item);
            }
        } while (cursor.moveToNext());
        Collections.sort(groupList);
        return groupList;
    }

    public static Observable<List<BookSourceBean>> importSource(String string) {
        if (StringUtils.isTrimEmpty(string)) return null;
        if (StringUtils.isJsonType(string)) {
            return importBookSourceFromJson(string.trim())
                    .compose(RxUtils::toSimpleSingle);
        }
        if (NetworkUtil.isUrl(string)) {
            return BaseModelImpl.getInstance().getRetrofitString(StringUtils.getBaseUrl(string), "utf-8")
                    .create(IHttpGetApi.class)
                    .getWebContent(string, AnalyzeHeaders.getMap(null))
                    .flatMap(rsp -> importBookSourceFromJson(rsp.body()))
                    .compose(RxUtils::toSimpleSingle);
        }
        return Observable.error(new Exception("不是Json或Url格式"));
    }

    private static Observable<List<BookSourceBean>> importBookSourceFromJson(String json) {
        return Observable.create(e -> {
            List<BookSourceBean> bookSourceBeans = new ArrayList<>();
            if (StringUtils.isJsonArray(json)) {
                try {
                    bookSourceBeans = new Gson().fromJson(json, new TypeToken<List<BookSourceBean>>() {
                    }.getType());
                    for (BookSourceBean bookSourceBean : bookSourceBeans) {
                        if (bookSourceBean.containsGroup("删除")) {
                            DbHelper.getDaoSession().getBookSourceBeanDao().queryBuilder()
                                    .where(BookSourceBeanDao.Properties.BookSourceUrl.eq(bookSourceBean.getBookSourceUrl()))
                                    .buildDelete().executeDeleteWithoutDetachingEntities();
                        } else {
                            try {
                                new URL(bookSourceBean.getBookSourceUrl());
                                bookSourceBean.setSerialNumber(0);
                                addBookSource(bookSourceBean);
                            } catch (Exception exception) {
                                DbHelper.getDaoSession().getBookSourceBeanDao().queryBuilder()
                                        .where(BookSourceBeanDao.Properties.BookSourceUrl.eq(bookSourceBean.getBookSourceUrl()))
                                        .buildDelete().executeDeleteWithoutDetachingEntities();
                            }
                        }
                    }
                    e.onNext(bookSourceBeans);
                    e.onComplete();
                    return;
                } catch (Exception ignored) {
                }
            }
            if (StringUtils.isJsonObject(json)) {
                try {
                    BookSourceBean bookSourceBean = new Gson().fromJson(json, new TypeToken<BookSourceBean>() {
                    }.getType());
                    addBookSource(bookSourceBean);
                    bookSourceBeans.add(bookSourceBean);
                    e.onNext(bookSourceBeans);
                    e.onComplete();
                    return;
                } catch (Exception ignored) {
                }
            }
            e.onError(new Throwable("格式不对"));
        });
    }

}
