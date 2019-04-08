package org.wordpress.android.ui.posts

import android.app.Activity
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProvider
import android.arch.lifecycle.ViewModelProviders
import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import org.wordpress.android.R
import org.wordpress.android.WordPress
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.ui.ActionableEmptyView
import org.wordpress.android.ui.ActivityLauncher
import org.wordpress.android.ui.pages.SnackbarMessageHolder
import org.wordpress.android.ui.posts.adapters.PostListAdapter
import org.wordpress.android.ui.uploads.UploadService
import org.wordpress.android.ui.uploads.UploadUtils
import org.wordpress.android.ui.utils.UiHelpers
import org.wordpress.android.ui.utils.UiString
import org.wordpress.android.util.AccessibilityUtils
import org.wordpress.android.util.DisplayUtils
import org.wordpress.android.util.NetworkUtils
import org.wordpress.android.util.SiteUtils
import org.wordpress.android.util.ToastUtils
import org.wordpress.android.util.WPSwipeToRefreshHelper.buildSwipeToRefreshHelper
import org.wordpress.android.util.helpers.SwipeToRefreshHelper
import org.wordpress.android.util.image.ImageManager
import org.wordpress.android.util.widgets.CustomSwipeRefreshLayout
import org.wordpress.android.viewmodel.posts.PagedPostList
import org.wordpress.android.viewmodel.posts.PostListItemIdentifier.LocalPostId
import org.wordpress.android.viewmodel.posts.PostListViewModel
import org.wordpress.android.viewmodel.posts.PostListViewModel.PostListEmptyUiState
import org.wordpress.android.widgets.RecyclerItemDecoration
import javax.inject.Inject

private const val EXTRA_POST_LIST_AUTHOR_FILTER = "post_list_author_filter"
private const val EXTRA_POST_LIST_TYPE = "post_list_type"

class PostListFragment : Fragment() {
    @Inject internal lateinit var imageManager: ImageManager
    @Inject internal lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject internal lateinit var uiHelpers: UiHelpers
    private lateinit var viewModel: PostListViewModel

    private var swipeToRefreshHelper: SwipeToRefreshHelper? = null

    private var swipeRefreshLayout: CustomSwipeRefreshLayout? = null
    private var recyclerView: RecyclerView? = null
    private var actionableEmptyView: ActionableEmptyView? = null
    private var progressLoadMore: ProgressBar? = null

    private lateinit var nonNullActivity: Activity
    private lateinit var site: SiteModel

    private val postViewHolderConfig: PostViewHolderConfig by lazy {
        val displayWidth = DisplayUtils.getDisplayPixelWidth(context)
        val contentSpacing = nonNullActivity.resources.getDimensionPixelSize(R.dimen.content_margin)
        PostViewHolderConfig(
                // endList indicator height is hard-coded here so that its horizontal line is in the middle of the fab
                endlistIndicatorHeight = DisplayUtils.dpToPx(context, 74),
                photonWidth = displayWidth - contentSpacing * 2,
                photonHeight = nonNullActivity.resources.getDimensionPixelSize(R.dimen.reader_featured_image_height),
                isPhotonCapable = SiteUtils.isPhotonCapable(site),
                imageManager = imageManager
        )
    }

    private val postListAdapter: PostListAdapter by lazy {
        PostListAdapter(
                context = nonNullActivity,
                postViewHolderConfig = postViewHolderConfig,
                uiHelpers = uiHelpers
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonNullActivity = checkNotNull(activity)
        (nonNullActivity.application as WordPress).component().inject(this)

        val site: SiteModel? = if (savedInstanceState == null) {
            val nonNullIntent = checkNotNull(nonNullActivity.intent)
            nonNullIntent.getSerializableExtra(WordPress.SITE) as SiteModel?
        } else {
            savedInstanceState.getSerializable(WordPress.SITE) as SiteModel?
        }

        if (site == null) {
            ToastUtils.showToast(nonNullActivity, R.string.blog_not_found, ToastUtils.Duration.SHORT)
            nonNullActivity.finish()
        } else {
            this.site = site
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val authorFilter: AuthorFilterSelection = requireNotNull(arguments)
                .getSerializable(EXTRA_POST_LIST_AUTHOR_FILTER) as AuthorFilterSelection
        val postListType = requireNotNull(arguments).getSerializable(EXTRA_POST_LIST_TYPE) as PostListType
        viewModel = ViewModelProviders.of(this, viewModelFactory)
                .get<PostListViewModel>(PostListViewModel::class.java)
        viewModel.start(site, authorFilter, postListType)
        viewModel.pagedListData.observe(this, Observer {
            it?.let { pagedListData -> updatePagedListData(pagedListData) }
        })
        viewModel.emptyViewState.observe(this, Observer {
            it?.let { emptyViewState -> updateEmptyViewForState(emptyViewState) }
        })
        viewModel.isFetchingFirstPage.observe(this, Observer {
            swipeRefreshLayout?.isRefreshing = it == true
        })
        viewModel.isLoadingMore.observe(this, Observer {
            progressLoadMore?.visibility = if (it == true) View.VISIBLE else View.GONE
        })
        viewModel.postListAction.observe(this, Observer {
            it?.let { action -> handlePostListAction(requireActivity(), action) }
        })
        viewModel.postUploadAction.observe(this, Observer {
            it?.let { uploadAction -> handleUploadAction(uploadAction) }
        })
        viewModel.toastMessage.observe(this, Observer {
            it?.show(nonNullActivity)
        })
        viewModel.snackBarAction.observe(this, Observer {
            it?.let { snackBarHolder -> showSnackBar(snackBarHolder) }
        })
        viewModel.dialogAction.observe(this, Observer {
            val fragmentManager = requireNotNull(fragmentManager) { "FragmentManager can't be null at this point" }
            it?.show(nonNullActivity, fragmentManager, uiHelpers)
        })
        viewModel.scrollToPosition.observe(this, Observer {
            it?.let { index ->
                recyclerView?.scrollToPosition(index)
            }
        })
        StrictMode.setThreadPolicy(
                ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()   // or .detectAll() for all detectable problems
                        .penaltyLog()
                        .penaltyDialog()
                        .build()
        )
        StrictMode.setVmPolicy(
                VmPolicy.Builder()
                        .detectLeakedSqlLiteObjects()
                        .detectLeakedClosableObjects()
                        .penaltyLog()
                        .penaltyDeath()
                        .penaltyDeath()
                        .build()
        )
    }

    private fun showSnackBar(holder: SnackbarMessageHolder) {
        nonNullActivity.findViewById<View>(R.id.coordinator)?.let { parent ->
            val message = getString(holder.messageRes)
            val duration = AccessibilityUtils.getSnackbarDuration(nonNullActivity)
            val snackBar = Snackbar.make(parent, message, duration)
            if (holder.buttonTitleRes != null) {
                snackBar.setAction(getString(holder.buttonTitleRes)) {
                    holder.buttonAction()
                }
            }
            snackBar.addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    holder.onDismissAction()
                    super.onDismissed(transientBottomBar, event)
                }
            })
            snackBar.show()
        }
    }

    private fun handleUploadAction(action: PostUploadAction) {
        when (action) {
            is PostUploadAction.EditPostResult -> {
                UploadUtils.handleEditPostResultSnackbars(
                        nonNullActivity,
                        nonNullActivity.findViewById(R.id.coordinator),
                        action.data,
                        action.post,
                        action.site
                ) {
                    action.publishAction()
                }
            }
            is PostUploadAction.PublishPost -> {
                UploadUtils.publishPost(
                        nonNullActivity,
                        action.post,
                        action.site,
                        action.dispatcher
                )
            }
            is PostUploadAction.PostUploadedSnackbar -> {
                UploadUtils.onPostUploadedSnackbarHandler(
                        nonNullActivity,
                        nonNullActivity.findViewById(R.id.coordinator),
                        action.isError,
                        action.post,
                        action.errorMessage,
                        action.site,
                        action.dispatcher
                )
            }
            is PostUploadAction.MediaUploadedSnackbar -> {
                UploadUtils.onMediaUploadedSnackbarHandler(
                        nonNullActivity,
                        nonNullActivity.findViewById(R.id.coordinator),
                        action.isError,
                        action.mediaList,
                        action.site,
                        action.message
                )
            }
            is PostUploadAction.CancelPostAndMediaUpload -> {
                UploadService.cancelQueuedPostUploadAndRelatedMedia(nonNullActivity, action.post)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(WordPress.SITE, site)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.post_list_fragment, container, false)

        swipeRefreshLayout = view.findViewById(R.id.ptr_layout)
        recyclerView = view.findViewById(R.id.recycler_view)
        progressLoadMore = view.findViewById(R.id.progress)
        actionableEmptyView = view.findViewById(R.id.actionable_empty_view)

        val context = nonNullActivity
        val spacingVertical = context.resources.getDimensionPixelSize(R.dimen.margin_medium)
        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.addItemDecoration(RecyclerItemDecoration(0, spacingVertical))
        recyclerView?.adapter = postListAdapter

        swipeToRefreshHelper = buildSwipeToRefreshHelper(swipeRefreshLayout) {
            if (!NetworkUtils.isNetworkAvailable(nonNullActivity)) {
                swipeRefreshLayout?.isRefreshing = false
            } else {
                viewModel.fetchFirstPage()
            }
        }
        return view
    }

    fun handleEditPostResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            if (data != null && EditPostActivity.checkToRestart(data)) {
                ActivityLauncher.editPostOrPageForResult(
                        data, nonNullActivity, site,
                        data.getIntExtra(EditPostActivity.EXTRA_POST_LOCAL_ID, 0)
                )

                // a restart will happen so, no need to continue here
                return
            }

            viewModel.handleEditPostResult(data)
        }
    }

    private fun updatePagedListData(pagedListData: PagedPostList) {
        postListAdapter.submitList(pagedListData)
    }

    private fun updateEmptyViewForState(state: PostListEmptyUiState) {
        actionableEmptyView?.let { emptyView ->
            if (state.emptyViewVisible) {
                emptyView.visibility = View.VISIBLE
                uiHelpers.setTextOrHide(emptyView.title, state.title)
                uiHelpers.setImageOrHide(emptyView.image, state.imgResId)
                setupButtonOrHide(emptyView.button, state.buttonText, state.onButtonClick)
            } else {
                emptyView.visibility = View.GONE
            }
        }
    }

    private fun setupButtonOrHide(
        buttonView: Button,
        text: UiString?,
        onButtonClick: (() -> Unit)?
    ) {
        uiHelpers.setTextOrHide(buttonView, text)
        buttonView.setOnClickListener { onButtonClick?.invoke() }
    }

    fun onPositiveClickedForBasicDialog(instanceTag: String) {
        viewModel.onPositiveClickedForBasicDialog(instanceTag)
    }

    fun onNegativeClickedForBasicDialog(instanceTag: String) {
        viewModel.onNegativeClickedForBasicDialog(instanceTag)
    }

    fun onDismissByOutsideTouchForBasicDialog(instanceTag: String) {
        viewModel.onDismissByOutsideTouchForBasicDialog(instanceTag)
    }

    fun scrollToTargetPost(localPostId: LocalPostId) {
        viewModel.scrollToPost(localPostId)
    }

    companion object {
        const val TAG = "post_list_fragment_tag"

        @JvmStatic
        fun newInstance(
            site: SiteModel,
            authorFilter: AuthorFilterSelection,
            postListType: PostListType
        ): PostListFragment {
            val fragment = PostListFragment()
            val bundle = Bundle()
            bundle.putSerializable(WordPress.SITE, site)
            bundle.putSerializable(EXTRA_POST_LIST_AUTHOR_FILTER, authorFilter)
            bundle.putSerializable(EXTRA_POST_LIST_TYPE, postListType)
            fragment.arguments = bundle
            return fragment
        }
    }
}
