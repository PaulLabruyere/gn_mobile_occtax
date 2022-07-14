package fr.geonature.occtax.ui.input.counting

import android.content.Context
import android.database.Cursor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.loader.app.LoaderManager
import androidx.loader.content.CursorLoader
import androidx.loader.content.Loader
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import fr.geonature.commons.data.ContentProviderAuthority
import fr.geonature.commons.data.GeoNatureModuleName
import fr.geonature.commons.data.entity.Dataset
import fr.geonature.commons.data.entity.DefaultNomenclature
import fr.geonature.commons.data.entity.DefaultNomenclatureWithType
import fr.geonature.commons.data.entity.Nomenclature
import fr.geonature.commons.data.entity.NomenclatureType
import fr.geonature.commons.data.entity.Taxonomy
import fr.geonature.commons.data.helper.ProviderHelper.buildUri
import fr.geonature.commons.util.KeyboardUtils.hideSoftKeyboard
import fr.geonature.occtax.R
import fr.geonature.occtax.input.CountingMetadata
import fr.geonature.occtax.input.Input
import fr.geonature.occtax.input.PropertyValue
import fr.geonature.occtax.settings.PropertySettings
import fr.geonature.occtax.ui.input.dialog.ChooseNomenclatureDialogFragment
import org.tinylog.kotlin.Logger
import javax.inject.Inject

/**
 * [Fragment] to let the user to edit additional counting information for the given [Input].
 *
 * @author S. Grimault
 */
@AndroidEntryPoint
class EditCountingMetadataFragment : Fragment(),
    ChooseNomenclatureDialogFragment.OnChooseNomenclatureDialogFragmentListener {

    @ContentProviderAuthority
    @Inject
    lateinit var authority: String

    @GeoNatureModuleName
    @Inject
    lateinit var moduleName: String

    private var progressBar: ProgressBar? = null
    private var emptyTextView: TextView? = null
    private var fab: ExtendedFloatingActionButton? = null

    private var listener: OnEditCountingMetadataFragmentListener? = null
    private var adapter: NomenclatureTypesRecyclerViewAdapter? = null
    private lateinit var taxonomy: Taxonomy
    private lateinit var countingMetadata: CountingMetadata

    private val loaderCallbacks = object : LoaderManager.LoaderCallbacks<Cursor> {
        override fun onCreateLoader(
            id: Int,
            args: Bundle?
        ): Loader<Cursor> {
            return when (id) {
                LOADER_NOMENCLATURE_TYPES -> CursorLoader(
                    requireContext(),
                    buildUri(
                        authority,
                        NomenclatureType.TABLE_NAME
                    ),
                    null,
                    null,
                    null,
                    null
                )
                LOADER_DEFAULT_NOMENCLATURE_VALUES -> CursorLoader(
                    requireContext(),
                    buildUri(
                        authority,
                        NomenclatureType.TABLE_NAME,
                        args?.getString(Dataset.COLUMN_MODULE) ?: "",
                        "default"
                    ),
                    null,
                    null,
                    null,
                    null
                )
                else -> throw IllegalArgumentException()
            }
        }

        override fun onLoadFinished(
            loader: Loader<Cursor>,
            data: Cursor?
        ) {
            if (data == null) {
                Logger.warn { "failed to load data from '${(loader as CursorLoader).uri}'" }

                return
            }

            when (loader.id) {
                LOADER_NOMENCLATURE_TYPES -> {
                    val defaultProperties = arguments?.getParcelableArray(ARG_PROPERTIES)
                        ?.map { it as PropertySettings }
                        ?.toTypedArray() ?: emptyArray()

                    adapter?.bind(
                        data,
                        *defaultProperties
                    )
                    loadDefaultNomenclatureValues()
                }
                LOADER_DEFAULT_NOMENCLATURE_VALUES -> {
                    val defaultMnemonicFilter = adapter?.defaultMnemonicFilter() ?: emptyList()
                    val defaultNomenclatureValues = mutableListOf<DefaultNomenclatureWithType>()
                    data.moveToFirst()

                    while (!data.isAfterLast) {
                        val defaultNomenclatureValue = DefaultNomenclatureWithType.fromCursor(data)

                        if (defaultNomenclatureValue != null && defaultMnemonicFilter.contains(
                                defaultNomenclatureValue.nomenclatureWithType?.type?.mnemonic
                            )
                        ) {
                            defaultNomenclatureValues.add(defaultNomenclatureValue)
                        }

                        data.moveToNext()
                    }

                    defaultNomenclatureValues.forEach {
                        val nomenclatureType = it.nomenclatureWithType?.type?.mnemonic
                            ?: return@forEach

                        if (countingMetadata.properties.contains(nomenclatureType)) {
                            return@forEach
                        }

                        countingMetadata.properties[nomenclatureType] =
                            PropertyValue.fromNomenclature(
                                nomenclatureType,
                                it.nomenclatureWithType
                            )
                    }

                    adapter?.setCountingMetata(countingMetadata)
                }
            }
        }

        override fun onLoaderReset(loader: Loader<Cursor>) {
            when (loader.id) {
                LOADER_NOMENCLATURE_TYPES -> adapter?.bind(null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        arguments?.also {
            taxonomy = it.getParcelable(ARG_TAXONOMY) ?: Taxonomy(
                Taxonomy.ANY,
                Taxonomy.ANY
            )
            countingMetadata = it.getParcelable(ARG_COUNTING_METADATA) ?: CountingMetadata()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_counting_edit,
            container,
            false
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        val recyclerView = view.findViewById<RecyclerView>(android.R.id.list)

        progressBar = view.findViewById(android.R.id.progress)
        emptyTextView = view.findViewById(android.R.id.empty)

        fab = view.findViewById(R.id.fab)
        fab?.apply {
            setOnClickListener {
                listener?.onSave(countingMetadata)
            }
        }

        // Set the adapter
        adapter = NomenclatureTypesRecyclerViewAdapter(object :
            NomenclatureTypesRecyclerViewAdapter.OnNomenclatureTypesRecyclerViewAdapterListener {

            override fun onAction(nomenclatureTypeMnemonic: String) {
                // workaround to force hide the soft keyboard
                view.rootView?.also {
                    hideSoftKeyboard(it)
                }

                val chooseNomenclatureDialogFragment = ChooseNomenclatureDialogFragment.newInstance(
                    nomenclatureTypeMnemonic,
                    taxonomy
                )
                chooseNomenclatureDialogFragment.show(
                    childFragmentManager,
                    CHOOSE_NOMENCLATURE_DIALOG_FRAGMENT
                )
            }

            override fun onMinMaxValues(
                min: Int,
                max: Int
            ) {
                countingMetadata.apply {
                    this.min = min
                    this.max = max
                }

                listener?.onCountingMetadata(countingMetadata)
            }

            override fun showEmptyTextView(show: Boolean) {
                progressBar?.visibility = View.GONE

                if (emptyTextView?.visibility == View.VISIBLE == show) {
                    return
                }

                if (show) {
                    emptyTextView?.startAnimation(
                        AnimationUtils.loadAnimation(
                            context,
                            android.R.anim.fade_in
                        )
                    )
                    emptyTextView?.visibility = View.VISIBLE
                } else {
                    emptyTextView?.startAnimation(
                        AnimationUtils.loadAnimation(
                            context,
                            android.R.anim.fade_out
                        )
                    )
                    emptyTextView?.visibility = View.GONE
                }
            }
        })

        with(recyclerView) {
            layoutManager = LinearLayoutManager(context)
            adapter = this@EditCountingMetadataFragment.adapter
        }

        loadNomenclatureTypes()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        if (context is OnEditCountingMetadataFragmentListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement OnEditCountingMetadataFragmentListener")
        }
    }

    override fun onSelectedNomenclature(
        nomenclatureType: String,
        nomenclature: Nomenclature
    ) {
        countingMetadata.properties[nomenclatureType] = PropertyValue.fromNomenclature(
            nomenclatureType,
            nomenclature
        )
        adapter?.setCountingMetata(countingMetadata)
        listener?.onCountingMetadata(countingMetadata)
    }

    private fun loadNomenclatureTypes() {
        progressBar?.visibility = View.VISIBLE
        
        LoaderManager.getInstance(this)
            .initLoader(
                LOADER_NOMENCLATURE_TYPES,
                null,
                loaderCallbacks
            )
    }

    private fun loadDefaultNomenclatureValues() {
        LoaderManager.getInstance(this)
            .initLoader(
                LOADER_DEFAULT_NOMENCLATURE_VALUES,
                bundleOf(
                    Pair(
                        DefaultNomenclature.COLUMN_MODULE,
                        moduleName
                    )
                ),
                loaderCallbacks
            )
    }

    /**
     * Callback used by [EditCountingMetadataFragment].
     */
    interface OnEditCountingMetadataFragmentListener {
        fun onCountingMetadata(countingMetadata: CountingMetadata)
        fun onSave(countingMetadata: CountingMetadata)
    }

    companion object {

        const val ARG_TAXONOMY = "arg_taxonomy"
        const val ARG_COUNTING_METADATA = "arg_counting_metadata"
        const val ARG_PROPERTIES = "arg_properties"

        private const val LOADER_NOMENCLATURE_TYPES = 1
        private const val LOADER_DEFAULT_NOMENCLATURE_VALUES = 2
        private const val CHOOSE_NOMENCLATURE_DIALOG_FRAGMENT =
            "choose_nomenclature_dialog_fragment"

        /**
         * Use this factory method to create a new instance of [EditCountingMetadataFragment].
         *
         * @return A new instance of [EditCountingMetadataFragment]
         */
        @JvmStatic
        fun newInstance(
            taxonomy: Taxonomy,
            countingMetadata: CountingMetadata? = null,
            vararg propertySettings: PropertySettings
        ) = EditCountingMetadataFragment().apply {
            arguments = Bundle().apply {
                putParcelable(
                    ARG_TAXONOMY,
                    taxonomy
                )
                countingMetadata?.let {
                    putParcelable(
                        ARG_COUNTING_METADATA,
                        countingMetadata
                    )
                }
                putParcelableArray(
                    ARG_PROPERTIES,
                    propertySettings
                )
            }
        }
    }
}
