package cn.sabercon.realworld.article

import cn.sabercon.realworld.user.UserFollows
import cn.sabercon.realworld.user.UserService
import cn.sabercon.realworld.user.Users
import cn.sabercon.realworld.util.*
import cn.sabercon.realworld.web.PageParams
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.springframework.stereotype.Service

@Service
class ArticleService(private val userService: UserService) {

    fun listArticle(
        userId: String?,
        tag: String?,
        author: String?,
        favorited: String?,
        pageParams: PageParams,
    ): List<ArticleModel> = tx {
        queryArticlePage(buildArticleQuery(tag, author, favorited), userId, pageParams)
    }

    fun countArticle(tag: String?, author: String?, favorited: String?): Long = tx {
        buildArticleQuery(tag, author, favorited).count()
    }

    fun listFeedArticle(userId: String, pageParams: PageParams): List<ArticleModel> = tx {
        queryArticlePage(buildFeedArticleQuery(userId), userId, pageParams)
    }

    fun countFeedArticle(userId: String): Long = tx {
        buildFeedArticleQuery(userId).count()
    }

    private fun buildArticleQuery(tag: String?, author: String?, favorited: String?): Query {
        var columnSet: ColumnSet = Articles
        if (tag != null) {
            columnSet = columnSet.innerJoin(ArticleTags, { Articles.id }, { articleId })
                .innerJoin(Tags, { ArticleTags.tagId }, { id }) { Tags.name eq tag }
        }
        if (author != null) {
            val authors = Users.alias("authors")
            val authorId = authors[Users.id]
            val authorUsername = authors[Users.username]
            columnSet = columnSet.innerJoin(authors, { Articles.authorId }, { authorId }) { authorUsername eq author }
        }
        if (favorited != null) {
            columnSet = columnSet.innerJoin(ArticleFavorites, { Articles.id }, { articleId })
                .innerJoin(Users, { ArticleFavorites.userId }, { id }) { Users.username eq favorited }
        }
        return columnSet.slice(Articles.columns).selectAll()
    }

    private fun buildFeedArticleQuery(userId: String): Query {
        return Articles.innerJoin(UserFollows, { authorId }, { followeeId })
            .slice(Articles.columns)
            .select { UserFollows.followerId eq EntityID(userId, Users) }
    }

    private fun queryArticlePage(query: Query, userId: String?, pageParams: PageParams): List<ArticleModel> {
        return query.orderBy(Articles.createdAt to SortOrder.DESC)
            .limit(pageParams.limit, pageParams.offset)
            .let { Article.wrapRows(it).toList() }
            .with(Article::author, Article::tags)
            .map { toModel(userId, it) }
    }

    fun getArticle(userId: String?, slug: String): ArticleModel = tx {
        toModel(userId, getBySlug(slug))
    }

    fun createArticle(userId: String, payload: ArticleCreateRequest.Article): ArticleModel = try {
        tx {
            val article = Article.new {
                title = payload.title
                slug = payload.title.slugify()
                description = payload.description
                body = payload.body
                author = userService.getById(userId)
                tags = SizedCollection(upsertTags(payload.tagList))
            }
            toModel(userId, article)
        }
    } catch (e: ExposedSQLException) {
        when {
            e.isUniqueConstraintException("article_slug_key") -> unprocessable("Slug already exists")
            else -> throw e
        }
    }

    fun updateArticle(userId: String, slug: String, payload: ArticleUpdateRequest.Article): ArticleModel = try {
        tx {
            val article = getBySlug(slug)
            if (article.author.id.value != userId) forbidden("Not author")

            payload.title?.let {
                article.title = it
                article.slug = it.slugify()
            }
            payload.description?.let { article.description = it }
            payload.body?.let { article.body = it }
            toModel(userId, article)
        }
    } catch (e: ExposedSQLException) {
        when {
            e.isUniqueConstraintException("article_slug_key") -> unprocessable("Slug already exists")
            else -> throw e
        }
    }

    fun deleteArticle(userId: String, slug: String): Unit = tx {
        val (articleId, authorId) = Articles.slice(Articles.id, Articles.authorId)
            .select { Articles.slug eq slug }
            .single().let { it[Articles.id] to it[Articles.authorId] }
        if (authorId.value != userId) forbidden("Not author")

        Articles.deleteWhere { id eq articleId }
        ArticleTags.deleteWhere { this.articleId eq articleId }
        ArticleFavorites.deleteWhere { this.articleId eq articleId }
    }

    fun favoriteArticle(userId: String, slug: String): ArticleModel = tx {
        val article = getBySlug(slug)
        ArticleFavorites.insertIgnore {
            it[this.userId] = EntityID(userId, Users)
            it[articleId] = article.id
        }
        toModel(userId, article, true)
    }

    fun unfavoriteArticle(userId: String, slug: String): ArticleModel = tx {
        val article = getBySlug(slug)
        ArticleFavorites.deleteWhere {
            this.userId eq EntityID(userId, Users) and (articleId eq article.id)
        }
        toModel(userId, article, false)
    }

    fun listTag(): List<String> = tx {
        Tag.all().map { it.name }
    }

    fun getBySlug(slug: String): Article {
        return Article.find { Articles.slug eq slug }.single()
    }

    private fun isFavorited(userId: String?, article: Article): Boolean {
        return userId != null && ArticleFavorites
            .exists { ArticleFavorites.userId eq EntityID(userId, Users) and (articleId eq article.id) }
    }

    private fun countFavorite(article: Article): Long {
        return ArticleFavorites.select { ArticleFavorites.articleId eq article.id }.count()
    }

    private fun upsertTags(tags: List<String>): List<Tag> {
        Tags.batchInsert(tags, ignore = true) { tag -> this[Tags.name] = tag }
        return Tag.find { Tags.name inList tags }.toList()
    }

    private fun toModel(
        userId: String?,
        article: Article,
        favorited: Boolean = isFavorited(userId, article),
    ): ArticleModel {
        return ArticleModel.from(
            article,
            favorited,
            countFavorite(article),
            userService.isFollowed(userId, article.author),
        )
    }
}
