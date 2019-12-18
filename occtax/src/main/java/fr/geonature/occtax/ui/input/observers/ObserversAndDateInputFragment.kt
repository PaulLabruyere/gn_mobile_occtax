package fr.geonature.occtax.ui.input.observers

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.util.Pair
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.loader.app.LoaderManager
import androidx.loader.content.CursorLoader
import androidx.loader.content.Loader
import fr.geonature.commons.data.Dataset
import fr.geonature.commons.data.DefaultNomenclatureWithType
import fr.geonature.commons.data.InputObserver
import fr.geonature.commons.data.NomenclatureType
import fr.geonature.commons.data.helper.Provider.buildUri
import fr.geonature.commons.input.AbstractInput
import fr.geonature.occtax.R
import fr.geonature.occtax.input.Input
import fr.geonature.occtax.input.NomenclatureTypeViewType
import fr.geonature.occtax.input.PropertyValue
import fr.geonature.occtax.ui.dataset.DatasetListActivity
import fr.geonature.occtax.ui.input.IInputFragment
import fr.geonature.occtax.ui.input.InputPagerFragmentActivity
import fr.geonature.occtax.ui.observers.InputObserverListActivity
import fr.geonature.occtax.ui.shared.dialog.DatePickerDialogFragment
import fr.geonature.occtax.ui.shared.view.ListItemActionView
import fr.geonature.occtax.util.SettingsUtils.getDefaultDatasetId
import fr.geonature.occtax.util.SettingsUtils.getDefaultObserverId
import fr.geonature.viewpager.ui.AbstractPagerFragmentActivity
import fr.geonature.viewpager.ui.IValidateFragment
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Selected observer and current date as first {@code Fragment} used by [InputPagerFragmentActivity].
 *
 * @author [S. Grimault](mailto:sebastien.grimault@gmail.com)
 */
class ObserversAndDateInputFragment : Fragment(),
                                      IValidateFragment,
                                      IInputFragment {

    private var input: Input? = null
    private val selectedInputObservers: MutableList<InputObserver> = mutableListOf()
    private var selectedDataset: Dataset? = null

    private var selectedInputObserversActionView: ListItemActionView? = null
    private var selectedDatasetActionView: ListItemActionView? = null
    private var inputDateActionView: ListItemActionView? = null

    private val loaderCallbacks = object : LoaderManager.LoaderCallbacks<Cursor> {
        override fun onCreateLoader(
                id: Int,
                args: Bundle?): Loader<Cursor> {

            return when (id) {
                LOADER_OBSERVERS_IDS -> CursorLoader(requireContext(),
                                                     buildUri(InputObserver.TABLE_NAME,
                                                              args?.getLongArray(KEY_SELECTED_INPUT_OBSERVER_IDS)?.joinToString(",")
                                                                      ?: ""),
                                                     null,
                                                     null,
                                                     null,
                                                     null)
                LOADER_DATASET_ID -> CursorLoader(requireContext(),
                                                  buildUri(Dataset.TABLE_NAME,
                                                           args?.getLong(KEY_SELECTED_DATASET_ID,
                                                                         -1).toString()),
                                                  null,
                                                  null,
                                                  null,
                                                  null)
                LOADER_DEFAULT_NOMENCLATURE_VALUES -> CursorLoader(requireContext(),
                                                                   buildUri(NomenclatureType.TABLE_NAME,
                                                                            "occtax",
                                                                            "default"),
                                                                   null,
                                                                   null,
                                                                   null,
                                                                   null)
                else -> throw IllegalArgumentException()
            }
        }

        override fun onLoadFinished(
                loader: Loader<Cursor>,
                data: Cursor?) {

            if (data == null) {
                Log.w(TAG,
                      "Failed to load data from '${(loader as CursorLoader).uri}'")

                return
            }

            when (loader.id) {
                LOADER_OBSERVERS_IDS -> {
                    if (data.moveToFirst()) {
                        selectedInputObservers.clear()

                        while (!data.isAfterLast) {
                            val selectedInputObserver = InputObserver.fromCursor(data)

                            if (selectedInputObserver != null) {
                                selectedInputObservers.add(selectedInputObserver)
                            }

                            data.moveToNext()
                        }

                        updateSelectedObserversActionView(selectedInputObservers)
                    }
                }
                LOADER_DATASET_ID -> {
                    if (data.moveToFirst()) {
                        selectedDataset = Dataset.fromCursor(data)
                        updateSelectedDatasetActionView(selectedDataset)
                    }
                }
                LOADER_DEFAULT_NOMENCLATURE_VALUES -> {
                    data.moveToFirst()

                    val defaultMnemonicFilter = Input.defaultPropertiesMnemonic.asSequence()
                        .filter { it.second == NomenclatureTypeViewType.NOMENCLATURE_TYPE }
                        .map { it.first }
                        .toList()

                    while (!data.isAfterLast) {
                        val defaultNomenclatureValue = DefaultNomenclatureWithType.fromCursor(data)
                        val mnemonic = defaultNomenclatureValue?.nomenclatureWithType?.type?.mnemonic

                        if (defaultNomenclatureValue != null && !mnemonic.isNullOrBlank() && defaultMnemonicFilter.contains(mnemonic)) {
                            input?.properties?.set(mnemonic,
                                                   PropertyValue.fromNomenclature(mnemonic,
                                                                                  defaultNomenclatureValue.nomenclatureWithType))
                        }

                        data.moveToNext()
                    }

                    (activity as AbstractPagerFragmentActivity?)?.validateCurrentPage()

                    if (input?.properties?.isNotEmpty() == false) {
                        val context = context ?: return

                        Toast.makeText(context,
                                       R.string.toast_input_default_properties_loading_failed,
                                       Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }
        }

        override fun onLoaderReset(loader: Loader<Cursor>) {
            when (loader.id) {
                LOADER_OBSERVERS_IDS -> selectedInputObservers.clear()
                LOADER_DATASET_ID -> selectedDataset = null
            }
        }
    }

    private val onCalendarSetListener = object : DatePickerDialogFragment.OnCalendarSetListener {
        override fun onCalendarSet(calendar: Calendar) {
            input?.date = calendar.time
            inputDateActionView?.setItems(listOf(Pair.create(DateFormat.format(getString(R.string.observers_and_date_date_format),
                                                                               calendar.time).toString(),
                                                             "")))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val supportFragmentManager = activity?.supportFragmentManager ?: return

        val dialogFragment = supportFragmentManager.findFragmentByTag(DATE_PICKER_DIALOG_FRAGMENT) as DatePickerDialogFragment?
        dialogFragment?.setOnCalendarSetListener(onCalendarSetListener)
    }

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_observers_and_date_input,
                                    container,
                                    false)

        selectedInputObserversActionView = view.findViewById(R.id.selected_observers_action_view)
        selectedInputObserversActionView?.setListener(object : ListItemActionView.OnListItemActionViewListener {
            override fun onAction() {
                val context = context ?: return

                startActivityForResult(InputObserverListActivity.newIntent(context,
                                                                           ListView.CHOICE_MODE_MULTIPLE,
                                                                           selectedInputObservers),
                                       LOADER_OBSERVERS_IDS)
            }
        })

        selectedDatasetActionView = view.findViewById(R.id.selected_dataset_action_view)
        selectedDatasetActionView?.setListener(object : ListItemActionView.OnListItemActionViewListener {
            override fun onAction() {
                val context = context ?: return

                startActivityForResult(DatasetListActivity.newIntent(context,
                                                                     selectedDataset),
                                       LOADER_DATASET_ID)
            }
        })

        inputDateActionView = view.findViewById(R.id.input_date_action_view)
        inputDateActionView?.setListener(object : ListItemActionView.OnListItemActionViewListener {
            override fun onAction() {
                val supportFragmentManager = activity?.supportFragmentManager ?: return
                val datePickerDialogFragment = DatePickerDialogFragment()
                datePickerDialogFragment.setOnCalendarSetListener(onCalendarSetListener)
                datePickerDialogFragment.show(supportFragmentManager,
                                              DATE_PICKER_DIALOG_FRAGMENT)
            }
        })

        return view
    }

    override fun onActivityResult(
            requestCode: Int,
            resultCode: Int,
            data: Intent?) {
        if ((resultCode != Activity.RESULT_OK) || (data == null)) {
            return
        }

        when (requestCode) {
            LOADER_OBSERVERS_IDS -> {
                selectedInputObservers.clear()
                selectedInputObservers.addAll(data.getParcelableArrayListExtra(InputObserverListActivity.EXTRA_SELECTED_INPUT_OBSERVERS))

                input?.also {
                    it.clearAllInputObservers()

                    if (selectedInputObservers.isEmpty()) {
                        val context = context ?: return
                        getDefaultObserverId(context).also { defaultObserverId -> if (defaultObserverId != null) it.setPrimaryInputObserverId(defaultObserverId) }
                    }

                    it.setAllInputObservers(selectedInputObservers)
                }

                updateSelectedObserversActionView(selectedInputObservers)
            }
            LOADER_DATASET_ID -> {
                selectedDataset = data.getParcelableExtra(DatasetListActivity.EXTRA_SELECTED_DATASET)

                input?.also {
                    it.datasetId = selectedDataset?.id
                }

                updateSelectedDatasetActionView(selectedDataset)
            }
        }
    }

    override fun getResourceTitle(): Int {
        return R.string.pager_fragment_observers_and_date_input_title
    }

    override fun pagingEnabled(): Boolean {
        return true
    }

    override fun validate(): Boolean {
        return this.input?.getAllInputObserverIds()?.isNotEmpty() ?: false && this.input?.datasetId != null && this.input?.properties?.isNotEmpty() == true
    }

    override fun refreshView() {
        setDefaultObserverFromSettings()
        setDefaultDatasetFromSettings()

        val selectedInputObserverIds = input?.getAllInputObserverIds() ?: emptySet()

        if (selectedInputObserverIds.isNotEmpty()) {
            LoaderManager.getInstance(this)
                .restartLoader(LOADER_OBSERVERS_IDS,
                               bundleOf(kotlin.Pair(KEY_SELECTED_INPUT_OBSERVER_IDS,
                                                    selectedInputObserverIds.toTypedArray().toLongArray())),
                               loaderCallbacks)
        }

        val selectedDatasetId = input?.datasetId

        if (selectedDatasetId != null) {
            LoaderManager.getInstance(this)
                .restartLoader(LOADER_DATASET_ID,
                               bundleOf(kotlin.Pair(KEY_SELECTED_DATASET_ID,
                                                    selectedDatasetId)),
                               loaderCallbacks)
        }

        LoaderManager.getInstance(this)
            .initLoader(LOADER_DEFAULT_NOMENCLATURE_VALUES,
                        null,
                        loaderCallbacks)

        inputDateActionView?.setItems(listOf(Pair.create(DateFormat.format(getString(R.string.observers_and_date_date_format),
                                                                           input?.date
                                                                                   ?: Date()).toString(),
                                                         "")))
    }

    override fun setInput(input: AbstractInput) {
        this.input = input as Input
    }

    private fun updateSelectedObserversActionView(selectedInputObservers: List<InputObserver>) {
        selectedInputObserversActionView?.setTitle(resources.getQuantityString(R.plurals.observers_and_date_selected_observers,
                                                                               selectedInputObservers.size,
                                                                               selectedInputObservers.size))
        selectedInputObserversActionView?.setItems(selectedInputObservers.map { inputObserver ->
            Pair.create(inputObserver.lastname?.toUpperCase(Locale.getDefault()) ?: "",
                        inputObserver.firstname)
        })
    }

    private fun updateSelectedDatasetActionView(selectedDataset: Dataset?) {
        selectedDatasetActionView?.setItems(if (selectedDataset == null) emptyList()
                                            else listOf(Pair.create(selectedDataset.name,
                                                                    selectedDataset.description)))
    }

    private fun setDefaultObserverFromSettings() {
        input?.run {
            if (this.getAllInputObserverIds().isEmpty()) {
                val context = context ?: return
                getDefaultObserverId(context).also { defaultObserverId -> if (defaultObserverId != null) this.setPrimaryInputObserverId(defaultObserverId) }
            }
        }
    }

    private fun setDefaultDatasetFromSettings() {
        input?.run {
            if (datasetId == null) {
                val context = context ?: return
                getDefaultDatasetId(context).also { defaultDatasetId ->
                    if (defaultDatasetId != null) this.datasetId = defaultDatasetId
                }
            }
        }
    }

    companion object {

        private val TAG = ObserversAndDateInputFragment::class.java.name
        private const val DATE_PICKER_DIALOG_FRAGMENT = "date_picker_dialog_fragment"
        private const val LOADER_OBSERVERS_IDS = 1
        private const val LOADER_DATASET_ID = 2
        private const val LOADER_DEFAULT_NOMENCLATURE_VALUES = 3
        private const val KEY_SELECTED_INPUT_OBSERVER_IDS = "selected_input_observer_ids"
        private const val KEY_SELECTED_DATASET_ID = "selected_dataset_id"

        /**
         * Use this factory method to create a new instance of [ObserversAndDateInputFragment].
         *
         * @return A new instance of [ObserversAndDateInputFragment]
         */
        @JvmStatic
        fun newInstance() = ObserversAndDateInputFragment()
    }
}